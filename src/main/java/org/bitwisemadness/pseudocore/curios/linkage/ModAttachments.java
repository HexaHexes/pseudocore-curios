package org.bitwisemadness.pseudocore.curios.linkage;

import com.mojang.serialization.Codec;
import java.util.LinkedHashMap;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * This addon's per-player attachments: the linkage map (#8) and the
 * active core index (#71).
 *
 * <p>The linkage attachment is registered like any NeoForge data attachment
 * - a codec for persistence plus {@code copyOnDeath()}, which is the
 * load-bearing decision: serializable entity attachments are <b>not</b>
 * copied to the new player on death by default, and every death-side
 * feature (the unreachable message's last-known position, and the
 * death-preserve target search) needs the records exactly while the player
 * is dead. That attachment is deliberately <b>unsynced</b>: the client
 * never reads it directly, it learns positions through the explicit
 * payloads the recall and waypoint flows send.</p>
 *
 * <p>The active core index is the mirror image: persisted and
 * {@code copyOnDeath} too, but <b>synced</b> - the selector strip (#71)
 * renders its frame from it, so NeoForge ships it to the owning client on
 * login and on every write ({@code setData} syncs automatically).</p>
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

    /**
     * Which of the six Curios slots the press acts on and the selector
     * strip frames (#71): a raw index, clamped and resolved at read time
     * so removing the active core can never strand the selection. Synced
     * to the owning client (the strip renders from it) and copied on
     * death like the linkage map.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> ACTIVE_CORE_INDEX =
            ATTACHMENT_TYPES.register("active_core_index",
                    () -> AttachmentType.builder(() -> 0)
                            .serialize(Codec.INT)
                            .copyOnDeath()
                            .sync(ByteBufCodecs.VAR_INT)
                            .build());

    private ModAttachments() {
    }

    /** The player's linkage map, creating the attachment on first use. */
    @SuppressWarnings("unchecked")
    public static LinkageMap of(Player player) {
        return (LinkageMap) player.getData(LINKAGE.get());
    }

    /** The stored selection before clamping (#71); see {@code ActiveCore}. */
    public static int activeCoreIndex(Player player) {
        return player.getData(ACTIVE_CORE_INDEX.get());
    }

    /** Stores the selection; NeoForge's attachment sync pushes it to the client (#71). */
    public static void setActiveCoreIndex(ServerPlayer player, int index) {
        player.setData(ACTIVE_CORE_INDEX.get(), index);
    }
}
