package org.bitwisemadness.pseudocore.curios.client;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.bitwisemadness.pseudocore.client.PseudocoreTintHandler;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.death.DeadMobComponent;
import org.bitwisemadness.pseudocore.curios.linkage.ModLinkageComponents;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.util.OrbColors;

/**
 * The client half of death-preserve (#14): the dead-core tint and the
 * death-information tooltip.
 *
 * <p>Registered manually behind {@code Dist.CLIENT} from the mod
 * constructor (the ClientHotkey pattern): both event types are
 * client-only classes and a dedicated server's dist cleaner has stripped
 * them, so an annotation-discovered listener would blow up at reflection
 * time there.</p>
 *
 * <p><b>Tint</b> (the locked rendering): a dead-filled core renders the
 * <b>empty orb texture</b> - free, because the {@code pseudocore:filled}
 * predicate keys on the live component, which migration stripped - tinted
 * with the dead mob's orb colour through the same ladder the live path
 * uses ({@link OrbColors#orbColorFor}, pseudocore #123). Registration
 * delegates everything else back to the public {@link PseudocoreTintHandler#TINT}:
 * {@code ItemColors.register} is a map put, and this addon loads after its
 * required pseudocore dependency, so this handler is the one that lands -
 * and it must answer <i>both</i> states, dead and alive. Body tint
 * (tintIndex 1) comes straight from the delegate.</p>
 *
 * <p><b>Tooltip</b>: one line, and only while no live mob shares the core
 * - the issue's "the death tooltip hides while CONTAINED_MOB exists", so
 * a recaptured core reads as the live creature it now is.</p>
 */
public final class DeathCoreClient {

    private DeathCoreClient() {
    }

    /** Called from the mod constructor behind {@code Dist.CLIENT}. */
    public static void register(IEventBus modBus) {
        modBus.addListener(DeathCoreClient::onRegisterColors);
        NeoForge.EVENT_BUS.addListener(DeathCoreClient::onTooltip);
    }

    /** Every pseudocore-namespace item: both lines, no IaF names touched. */
    private static void onRegisterColors(RegisterColorHandlersEvent.Item event) {
        Item[] cores = BuiltInRegistries.ITEM.stream()
                .filter(item -> BuiltInRegistries.ITEM.getKey(item)
                        .getNamespace().equals(PseudocoreCurios.MOD_ID))
                .toArray(Item[]::new);
        if (cores.length == 0) {
            return;
        }
        event.register((stack, tintIndex) -> {
            DeadMobComponent dead = stack.get(ModLinkageComponents.DEAD_MOB.get());
            if (dead != null && !stack.has(ModDataComponents.CONTAINED_MOB.get())) {
                // Locked rendering: the model already shows the empty art
                // (the filled predicate sees no live component); the orbs
                // take the dead mob's colour, body stays with the delegate.
                return tintIndex == 0
                        ? OrbColors.orbColorFor(dead.entityType(), dead.entityData()) | 0xFF000000
                        : PseudocoreTintHandler.TINT.getColor(stack, tintIndex);
            }
            return PseudocoreTintHandler.TINT.getColor(stack, tintIndex);
        }, cores);
    }

    private static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        DeadMobComponent dead = stack.get(ModLinkageComponents.DEAD_MOB.get());
        if (dead == null || stack.has(ModDataComponents.CONTAINED_MOB.get())) {
            return; // no death data, or a live mob has since won the core
        }
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(dead.entityType())
                .map(t -> (Component) t.getDescription())
                .orElseGet(() -> Component.literal(dead.entityType().getPath()));
        String cause = "a " + dead.damageType().getPath().replace('_', ' ');
        event.getToolTip().add(Component
                .translatable("pseudocore_curios.tooltip.preserved", type, cause)
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
