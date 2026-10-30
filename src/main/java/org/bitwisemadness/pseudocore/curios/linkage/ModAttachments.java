package org.bitwisemadness.pseudocore.curios.linkage;

import com.mojang.serialization.Codec;
import java.util.LinkedHashMap;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The per-player attachment behind linkage tracking (#8): one record per
 * released mob, keyed by linkage UUID.
 *
 * <p>Registered like any NeoForge data attachment - a codec for persistence
 * plus {@code copyOnDeath()}, which is the load-bearing decision: serializable
 * entity attachments are <b>not</b> copied to the new player on death by
 * default, and every death-side feature (the unreachable message's
 * last-known position, and the death-preserve target search) needs the records
 * exactly while the player is dead. The attachment is deliberately
 * <b>unsynced</b>: the client never reads it directly, it learns positions
 * through the explicit payloads the recall and waypoint flows send.</p>
 */
public final class ModAttachments {

    /**
     * Cap on tracked linkages per player (#18). Insertion-ordered map, oldest
     * evicted first on overflow - generous enough that no play session hits
     * it, tight enough that months of releases cannot grow the attachment
     * without bound.
     */
    public static final int MAX_LINKAGES = 128;

    /** The attachment's value: linkage UUID -> record, insertion-ordered. */
    public static class LinkageMap extends LinkedHashMap<String, LinkageRecord> {
        public LinkageMap() {
            super(8, 0.75F, false);
        }
    }

    public static final Codec<LinkageMap> LINKAGE_CODEC =
            Codec.unboundedMap(Codec.STRING, LinkageRecord.CODEC).xmap(map -> {
                LinkageMap linkage = new LinkageMap();
                linkage.putAll(map);
                return linkage;
            }, map -> map);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "pseudocore_curios");

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<LinkageMap>> LINKAGE =
            ATTACHMENT_TYPES.register("linkage", () -> AttachmentType.builder(LinkageMap::new)
                    .serialize(LINKAGE_CODEC)
                    .copyOnDeath()
                    .build());

    private ModAttachments() {
    }

    /** The player's linkage map, creating the attachment on first use. */
    @SuppressWarnings("unchecked")
    public static LinkageMap of(Player player) {
        return (LinkageMap) player.getData(LINKAGE.get());
    }
}
