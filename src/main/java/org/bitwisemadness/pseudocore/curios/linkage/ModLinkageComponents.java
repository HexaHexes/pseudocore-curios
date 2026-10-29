package org.bitwisemadness.pseudocore.curios.linkage;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;

/**
 * The linkage stamp a core receives when it releases its mob (#8): the UUID
 * tying this exact core to that exact mob, matching the tag on the mob and
 * the record in the player's attachment - three sides of one identity.
 *
 * <p>Deliberately this mod's own component rather than anything pseudocore
 * declares: base release paths never look at it (an unstamped core behaves
 * exactly as before), and only this addon reads it, so the base contract
 * stays one question - "filled or empty".</p>
 */
public final class ModLinkageComponents {

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "pseudocore_curios");

    /**
     * String-encoded UUID: the vanilla UUID codec writes an int-array tag,
     * which is opaque in every inspection tool a player or mod author will
     * ever open; a string reads as itself.
     */
    private static final Codec<UUID> STRING_UUID = Codec.STRING.xmap(
            UUID::fromString, UUID::toString);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> LINKAGE =
            DATA_COMPONENTS.registerComponentType("linkage", builder -> builder
                    .persistent(STRING_UUID)
                    .networkSynchronized(StreamCodec.composite(
                            ByteBufCodecs.STRING_UTF8, UUID::toString, UUID::fromString)));

    public static void register() {
    }

    private ModLinkageComponents() {
    }
}
