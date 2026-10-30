package org.bitwisemadness.pseudocore.curios.death;

import de.maxhenkel.corpse.corelib.death.Death;
import de.maxhenkel.corpse.corelib.death.DeathManager;
import de.maxhenkel.corpse.entities.CorpseEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.bitwisemadness.pseudocore.curios.linkage.ModLinkageComponents;

import java.util.List;
import java.util.UUID;

/**
 * The corpse leg of the death-preserve search (#14), deliberately its own
 * class: FML's automatic-subscriber scan calls {@code getDeclaredMethods}
 * on listener classes, which resolves every synthetic lambda parameter
 * eagerly - a {@code lambda$...} taking {@link CorpseEntity} made mod
 * construction throw {@code NoClassDefFoundError} whenever Corpse was not
 * installed. This class is never annotated, never scanned, and only loaded
 * behind the caller's {@code ModList} gate; its own method signatures are
 * all vanilla types, so even eager resolution would be safe.
 *
 * <p>History locates, the live entity receives: the newest death of this
 * player pins a position, the corpse entity standing there owns the
 * current inventory, and the mutation lands on that live list - never on
 * a historical copy (looted items must not reappear). A corpse in an
 * unloaded chunk is an absent leg: no force-loads on a damage event, the
 * same locked rule as recall.</p>
 */
final class CorpseSearch {

    private CorpseSearch() {
    }

    /** The owner's newest corpse's linkage-matching core, or null. */
    static ItemStack find(ServerLevel level, UUID owner, UUID linkage) {
        List<Death> deaths;
        try {
            deaths = DeathManager.getDeaths(level, owner);
        } catch (RuntimeException corpseUnreadable) {
            return null;
        }
        Death newest = null;
        for (Death death : deaths) {
            if (newest == null || death.getTimestamp() > newest.getTimestamp()) {
                newest = death;
            }
        }
        if (newest == null) {
            return null;
        }
        AABB area = new AABB(newest.getBlockPos()).inflate(3.0D);
        CorpseEntity corpse = level.getEntitiesOfClass(CorpseEntity.class, area,
                        candidate -> candidate.getDeath() != null
                                && owner.equals(candidate.getDeath().getPlayerUUID()))
                .stream().findFirst().orElse(null);
        if (corpse == null) {
            return null;
        }
        for (ItemStack stack : corpse.getDeath().getMainInventory()) {
            if (DeathPreserveEvents.isPreservable(stack, linkage)) {
                return stack; // mutated in place: it already sits in the corpse
            }
        }
        return null;
    }
}
