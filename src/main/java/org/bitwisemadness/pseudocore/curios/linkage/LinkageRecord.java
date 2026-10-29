package org.bitwisemadness.pseudocore.curios.linkage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * One tracked release: which mob a core released, and where it was last seen.
 *
 * <p>Stored per player in the {@link ModAttachments#LINKAGE attachment},
 * keyed by the linkage UUID that also lives on the core and the mob's entity
 * tag - the three-sided identity every later feature resolves through (#8).
 * The mob is identified by its <b>UUID as a string</b> and its dimension by
 * id rather than {@code ResourceKey}, which keeps the codec trivial and
 * NBT-friendly; lookups convert back.</p>
 *
 * @param mobUuid    the released mob's UUID
 * @param dimension  the dimension the mob was released into (id string)
 * @param x          last-known position - refreshed from the entity at
 * @param y          release and again whenever the entity's chunk unloads,
 * @param z          which is the last moment it is reachable (#8)
 * @param name       the display name captured at release/refresh (custom name
 *                   if the mob has one, else its type name)
 */
public record LinkageRecord(String mobUuid, String dimension,
                            double x, double y, double z, String name) {

    public static final Codec<LinkageRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("mob").forGetter(LinkageRecord::mobUuid),
            Codec.STRING.fieldOf("dimension").forGetter(LinkageRecord::dimension),
            Codec.DOUBLE.fieldOf("x").forGetter(LinkageRecord::x),
            Codec.DOUBLE.fieldOf("y").forGetter(LinkageRecord::y),
            Codec.DOUBLE.fieldOf("z").forGetter(LinkageRecord::z),
            Codec.STRING.fieldOf("name").forGetter(LinkageRecord::name)
    ).apply(instance, LinkageRecord::new));

    public static LinkageRecord of(ResourceLocation dimension, String mobUuid,
                                   double x, double y, double z, String name) {
        return new LinkageRecord(mobUuid, dimension.toString(), x, y, z, name);
    }
}
