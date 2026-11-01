package org.bitwisemadness.pseudocore.curios.death;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * The death-preserve record a core carries after a tracked mob dies into
 * it (#14): what the mob was, how it died, and when.
 *
 * <p>The same shape as pseudocore's {@code ContainedMobComponent} for the
 * mob itself (type, UUID, full NBT - the pair {@code orbColorFor} reads),
 * plus the death facts. Base pseudocore never sees this component: a core
 * holding only {@code dead_mob} is <b>empty</b> to the base contract, so
 * release paths stay inert for free, the {@code pseudocore:filled}
 * predicate falls to the empty art (locked rendering), and a live capture
 * into the same core simply coexists until the clear gesture wipes the
 * residue.</p>
 *
 * @param entityType  the registry id of the preserved entity type
 * @param entityUuid  the preserved entity's UUID
 * @param entityData  its full NBT from {@code Entity#saveWithoutId}
 * @param damageType  the killing damage type's registry id (identity -
 *                    what the config whitelist matched)
 * @param damageMsgId the damage type's message id (display - what
 *                    {@code death.attack.*} translation keys are built on)
 * @param timestamp   wall-clock millis of the preservation
 */
public record DeadMobComponent(ResourceLocation entityType, UUID entityUuid,
                               CompoundTag entityData, ResourceLocation damageType,
                               String damageMsgId, long timestamp) {

    public static final Codec<DeadMobComponent> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("entityType").forGetter(DeadMobComponent::entityType),
                    UUIDUtil.CODEC.fieldOf("entityUuid").forGetter(DeadMobComponent::entityUuid),
                    CompoundTag.CODEC.fieldOf("entityData").forGetter(DeadMobComponent::entityData),
                    ResourceLocation.CODEC.fieldOf("damageType").forGetter(DeadMobComponent::damageType),
                    Codec.STRING.fieldOf("damageMsgId").forGetter(DeadMobComponent::damageMsgId),
                    Codec.LONG.fieldOf("timestamp").forGetter(DeadMobComponent::timestamp)
            ).apply(instance, DeadMobComponent::new));

    /** Network codec; {@link ByteBufCodecs#COMPOUND_TAG} satisfies the bound. */
    public static final StreamCodec<RegistryFriendlyByteBuf, DeadMobComponent> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, DeadMobComponent::entityType,
                    UUIDUtil.STREAM_CODEC, DeadMobComponent::entityUuid,
                    ByteBufCodecs.COMPOUND_TAG, DeadMobComponent::entityData,
                    ResourceLocation.STREAM_CODEC, DeadMobComponent::damageType,
                    ByteBufCodecs.STRING_UTF8, DeadMobComponent::damageMsgId,
                    ByteBufCodecs.VAR_LONG, DeadMobComponent::timestamp,
                    DeadMobComponent::new
            );
}
