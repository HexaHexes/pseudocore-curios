package org.bitwisemadness.pseudocore.curios.linkage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;

import java.util.Iterator;
import java.util.UUID;

/**
 * The linkage API (#8): three sides of one identity - a stamp on the core, a
 * tag on the mob, a record in the player's attachment - plus the record
 * lifecycle (#18).
 *
 * <p>The tag rides the mob's persistent data, so it survives chunk unloads
 * and travels with the entity's saved NBT; the attachment rides the player
 * and survives death ({@code copyOnDeath}); the stamp rides the core and
 * survives inventory moves and, pending verification, Gear Evolution.</p>
 */
public final class LinkageManager {

    private static final String TAG_KEY = "pseudocore_curios_linkage";
    private static final String TAG_LINKAGE = "linkage";
    private static final String TAG_OWNER = "owner";

    private LinkageManager() {
    }

    /**
     * Called from the release listener: stamps the core, tags the mob, and
     * records the sighting in the releasing player's attachment. Returns the
     * linkage UUID shared by all three sides.
     *
     * <p>Releases without a player context (a dispenser firing a core) have
     * no attachment to record into and are deliberately not tracked - recall
     * and death-preserve are player features.</p>
     */
    public static UUID remember(Player releaser, ItemStack core, Entity mob) {
        UUID linkage = UUID.randomUUID();
        core.set(ModLinkageComponents.LINKAGE.get(), linkage);

        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_LINKAGE, linkage.toString());
        tag.putString(TAG_OWNER, releaser.getUUID().toString());
        mob.getPersistentData().put(TAG_KEY, tag);

        record(releaser, mob, linkage);

        // #18: the cap keeps insertion order, so the head is the oldest.
        var map = ModAttachments.of(releaser);
        while (map.size() > ModAttachments.MAX_LINKAGES) {
            Iterator<String> oldest = map.keySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            String evicted = oldest.next();
            oldest.remove();
            PseudocoreCurios.LOGGER.debug("Evicted oldest linkage {} (cap {})",
                    evicted, ModAttachments.MAX_LINKAGES);
        }
        return linkage;
    }

    /**
     * Write or refresh the player's record for a tagged mob - the release
     * sighting and the chunk-unload refresh share this, so the record always
     * holds the entity's freshest reachable position.
     */
    public static void record(Player owner, Entity mob, UUID linkage) {
        var position = mob.position();
        ModAttachments.of(owner).put(linkage.toString(), LinkageRecord.of(
                mob.level().dimension().location(), mob.getUUID().toString(),
                position.x, position.y, position.z, mob.getName().getString()));
    }

    /** The mob's linkage UUID from its tag, or null when untagged. */
    public static UUID taggedLinkage(Entity mob) {
        CompoundTag tag = tagOf(mob);
        return tag == null ? null : UUID.fromString(tag.getString(TAG_LINKAGE));
    }

    /** The player who released the tagged mob, or null when untagged. */
    public static UUID taggedOwner(Entity mob) {
        CompoundTag tag = tagOf(mob);
        return tag == null ? null : UUID.fromString(tag.getString(TAG_OWNER));
    }

    /** The stored record for a linkage, or null when evicted/unknown. */
    public static LinkageRecord find(Player player, UUID linkage) {
        return linkage == null ? null : ModAttachments.of(player).get(linkage.toString());
    }

    /**
     * Drop a record (#18: recall success and death-preserve both consume
     * theirs). The core's stamp and - if the mob still exists - its tag are
     * deliberately untouched: only the player-facing record is forgotten.
     */
    public static void forget(Player player, UUID linkage) {
        if (linkage != null) {
            ModAttachments.of(player).remove(linkage.toString());
        }
    }

    private static CompoundTag tagOf(Entity mob) {
        CompoundTag tag = mob.getPersistentData();
        return tag.contains(TAG_KEY) ? tag.getCompound(TAG_KEY) : null;
    }
}
