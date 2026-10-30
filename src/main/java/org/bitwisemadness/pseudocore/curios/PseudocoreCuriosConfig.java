package org.bitwisemadness.pseudocore.curios;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * This addon's server config (#14): the death-preserve switches.
 *
 * <p>SERVER type deliberately: these gate what happens to dying mobs and
 * which inventories may be touched - gameplay rules a server owner sets,
 * never client preferences. The client-only tint preference lives in
 * pseudocore's own client config, not here.</p>
 *
 * <p>The widening rule the issue locks: {@code deathSaveAllowNonSlotCores}
 * and {@code deathSaveSearchCorpses} widen <b>where the search looks</b>,
 * never what may be written - the linkage identity stays absolute, only
 * the core that released the mob can receive it.</p>
 */
public final class PseudocoreCuriosConfig {

    public static final Common COMMON;
    public static final ModConfigSpec COMMON_SPEC;

    static {
        final var pair = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        COMMON_SPEC = pair.getRight();
    }

    private PseudocoreCuriosConfig() {
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, COMMON_SPEC);
    }

    /** Whether a configured source may ever preserve instead of kill. */
    public static boolean deathSaveEnabled() {
        try {
            return COMMON.deathSaveEnabled.get();
        } catch (RuntimeException notLoadedYet) {
            return false;
        }
    }

    /** The damage types that trigger preservation; empty = save from nothing. */
    public static boolean damageSourceAllowed(String typeId) {
        try {
            return COMMON.deathSaveDamageSources.get().contains(typeId);
        } catch (RuntimeException notLoadedYet) {
            return false;
        }
    }

    /** May the search widen beyond the Curios slot into the inventory? */
    public static boolean allowNonSlotCores() {
        try {
            return COMMON.deathSaveAllowNonSlotCores.get();
        } catch (RuntimeException notLoadedYet) {
            return false;
        }
    }

    /** May the search also look inside the owner's corpse (Corpse mod)? */
    public static boolean searchCorpses() {
        try {
            return COMMON.deathSaveSearchCorpses.get();
        } catch (RuntimeException notLoadedYet) {
            return false;
        }
    }

    /**
     * What the clear gesture does to a filled core (#15); fail-open to
     * {@link ClearActionMode#RELEASE} - the destructive path is only ever
     * reached by an explicit, valid config value.
     */
    public static org.bitwisemadness.pseudocore.curios.hotkey.ClearActionMode clearAction() {
        try {
            return org.bitwisemadness.pseudocore.curios.hotkey.ClearActionMode
                    .parse(COMMON.filledCoreClearAction.get());
        } catch (RuntimeException notLoadedYet) {
            return org.bitwisemadness.pseudocore.curios.hotkey.ClearActionMode.RELEASE;
        }
    }

    public static final class Common {
        public final ModConfigSpec.BooleanValue deathSaveEnabled;
        public final ModConfigSpec.ConfigValue<List<? extends String>> deathSaveDamageSources;
        public final ModConfigSpec.BooleanValue deathSaveAllowNonSlotCores;
        public final ModConfigSpec.BooleanValue deathSaveSearchCorpses;
        public final ModConfigSpec.ConfigValue<String> filledCoreClearAction;

        public Common(ModConfigSpec.Builder builder) {
            builder.push("deathSave");
            deathSaveEnabled = builder
                    .comment(
                            "Master switch: may a tracked mob about to die from a whitelisted",
                            "source be preserved into the core that released it instead of",
                            "dying? Off (default): deaths are always deaths.")
                    .define("enabled", false);
            deathSaveDamageSources = builder
                    .comment(
                            "The damage types that may trigger preservation, as registry ids.",
                            "Only these sources are ever intercepted; everything else kills",
                            "normally by omission - void stays lethal unless listed here.",
                            "Empty list = preserve from nothing even when enabled.")
                    .defineList("damageSources",
                            List.of("minecraft:mob_attack", "minecraft:player_attack"),
                            entry -> entry instanceof String s && s.contains(":"));
            deathSaveAllowNonSlotCores = builder
                    .comment(
                            "Widen the core SEARCH beyond the Curios slot to the player's",
                            "inventory (hotbar included; offhand and armor excluded). This",
                            "widens where the search looks, never what may be written:",
                            "only the linkage-matching core ever receives the mob.")
                    .define("allowNonSlotCores", false);
            deathSaveSearchCorpses = builder
                    .comment(
                            "Also search the origin player's corpse inventory (the Corpse",
                            "mod). Dead config without Corpse installed - gated by mod",
                            "presence at runtime too.")
                    .define("searchCorpses", true);
            filledCoreClearAction = builder
                    .comment(
                            "What sneak + right-click on air does to a FILLED core (#15):",
                            "'release' (default): the mob spawns at your feet - the same",
                            "result as the hotkey release. 'destroy': the stored NBT is",
                            "deleted after a second sneak-click within 5 seconds, so a",
                            "stray gesture can never eat a dragon. Any other value",
                            "behaves as 'release'. A core holding only preserved death",
                            "data is wiped instantly regardless of this setting - that",
                            "is the record's explicit exit.")
                    .define("filledCoreClearAction", "release");
            builder.pop();
        }
    }
}
