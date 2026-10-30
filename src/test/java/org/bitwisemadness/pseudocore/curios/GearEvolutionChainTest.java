package org.bitwisemadness.pseudocore.curios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Gear Evolution chain data (#1, relocated here from pseudocore #93 per the
 * pack decision that Tensura/Gear Evolution integration belongs to this
 * companion mod), in the same shape Lightman's wallets
 * ({@code data/lightmans_tensura/addon_chains}) and Traveler Tool Belts
 * ({@code data/tensura_tool_belt/addon_chains}) already ship: one JSON per core
 * tier with {@code item}/{@code minEP}/{@code maxEP}/{@code epGain} and an
 * {@code evolution} target, terminals without one.
 *
 * <p>Files ship from this mod's own namespace ({@code data/pseudocore_curios}),
 * following the wallet and belt precedent - Gear Evolution's listener scans
 * every namespace - while the {@code item} and {@code evolution} values name
 * Pseudocore's items, which is what the ladders actually chain.</p>
 *
 * <p>File-based, in the style of the org's other data suites: the junit source
 * set has no gson at compile time in this repo's wiring, so fields are
 * regex-extracted. The checks that matter are the relational ones: a typo'd
 * evolution target or a minEP that does not meet its predecessor's maxEP stalls
 * a ladder silently in game, which no test server run can see (Gear
 * Evolution's listener only runs where GE is installed).</p>
 *
 * <p>The EP caps are pinned to the settled #33 curve, which follows the
 * pack's <b>armor chains</b>: the vanilla rungs from the live
 * {@code Tensura - Vanilla Extensions} datapack (leather 5,500 through
 * netherite 175,000), the Tensura rungs from Tensura: Reincarnated's own
 * jar (identical to the {@code Default Extensions} datapack), dragon tiers
 * on the jar's main spine starting at the low_magisteel rung, and the two
 * Option-A tie-breaks (mithril 400,000, adamantite 1,250,000). They
 * deliberately no longer mirror {@code PseudocoreConfig}'s capture-limit
 * defaults: what a tier may catch and how hard it evolves are different
 * questions. {@code epGain} is copied verbatim from the armor ladders and
 * may be <b>omitted</b> - GE's loader reads an absent value as 0.0, which
 * is exactly how the live armor files ship. The two uncapped terminal
 * tiers carry {@link #UNCAPPED_SENTINEL} - GE needs a number even for a
 * tier that must never cap out.</p>
 */
class GearEvolutionChainTest {

    /** The terminal tiers' maxEP: uncapped in config, a ceiling GE can hold. */
    private static final long UNCAPPED_SENTINEL = 100_000_000L;

    /**
     * The one sanctioned continuity exception (#33): netherite caps at
     * 175,000 but low_magisteel's whole band is 6,000-18,000 - the armor
     * chain itself descends across that boundary (netherite armor evolves
     * into an 18k-capped item), so this rung starts at the Tensura jar's
     * own floor instead of the previous maxEP.
     */
    private static final long DESCENT_FLOOR = 6_000L;

    private static final Path CHAINS =
            Path.of("src/main/resources/data/pseudocore_curios/addon_chains");

    /** The settled armor-aligned curve (#33): item plus maxEP. */
    private static final String[][] BEAST = {
            {"copper_pseudocore", "5500"},
            {"iron_pseudocore", "13500"},
            {"silver_pseudocore", "32000"},
            {"gold_pseudocore", "78000"},
            {"netherite_pseudocore", "175000"},
            {"low_magisteel_pseudocore", "18000"},
            {"high_magisteel_pseudocore", "52000"},
            {"pure_magisteel_pseudocore", "225000"},
            {"mithril_pseudocore", "400000"},
            {"orichalcum_pseudocore", "750000"},
            {"adamantite_pseudocore", "1250000"},
            {"hihiirokane_pseudocore", String.valueOf(UNCAPPED_SENTINEL)},
    };

    /** Dragon tiers on the Tensura jar's main spine (#33): item plus maxEP. */
    private static final String[][] DRAGON = {
            {"copper_dragon_pseudocore", "18000"},
            {"iron_dragon_pseudocore", "52000"},
            {"silver_dragon_pseudocore", "225000"},
            {"gold_dragon_pseudocore", "750000"},
            {"netherite_dragon_pseudocore", String.valueOf(UNCAPPED_SENTINEL)},
    };

    /**
     * The dragonsteel tiers (#27): parallel element branches after
     * netherite_dragon - not a linear ladder, so each one starts at the
     * IaF dragonsteel armor's own floor instead of a predecessor's
     * maxEP (the predecessor is uncapped - a ceiling, not a reachable
     * number), carries the uncapped sentinel, and stays terminal.
     */
    private static final long DRAGONSTEEL_ARMOR_FLOOR = 750_000L;

    private static final String[][] DRAGONSTEEL = {
            {"fire_dragonsteel_pseudocore", String.valueOf(UNCAPPED_SENTINEL)},
            {"ice_dragonsteel_pseudocore", String.valueOf(UNCAPPED_SENTINEL)},
            {"lightning_dragonsteel_pseudocore", String.valueOf(UNCAPPED_SENTINEL)},
    };

    private static final Pattern ITEM = Pattern.compile("\"item\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MIN = Pattern.compile("\"minEP\"\\s*:\\s*(\\d+)");
    private static final Pattern MAX = Pattern.compile("\"maxEP\"\\s*:\\s*(\\d+)");
    private static final Pattern GAIN = Pattern.compile("\"epGain\"\\s*:\\s*([0-9.]+)");
    private static final Pattern EVO = Pattern.compile("\"evolution\"\\s*:\\s*\"([^\"]+)\"");

    /** One chain file's fields; evolution null = terminal tier. */
    private record Chain(String item, long min, long max, String gain, String evolution) {
    }

    @Test
    void shipsEveryTierOfBothLaddersAndNothingElse() throws IOException {
        List<String> shipped = new ArrayList<>();
        try (var files = Files.list(CHAINS)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> shipped.add(p.getFileName().toString()));
        }
        assertEquals(BEAST.length + DRAGON.length + DRAGONSTEEL.length, shipped.size(),
                "all three ladders must ship in full - a missing tier stalls evolution mid-ladder");
        for (String[][] ladder : new String[][][]{BEAST, DRAGON, DRAGONSTEEL}) {
            for (String[] tier : ladder) {
                assertTrue(shipped.contains(tier[0] + ".json"),
                        "missing chain file for tier: " + tier[0]);
            }
        }
    }

    /**
     * The dragonsteel triple (#27): IaF's armor floor as each row's start,
     * the uncapped sentinel, terminal (parallel elements chain nowhere),
     * declared item, numeric gain.
     */
    @Test
    void dragonsteelTiersCarryTheArmorFloorAndStayTerminal() throws IOException {
        for (String[] tier : DRAGONSTEEL) {
            Chain chain = readChain(tier[0]);
            String label = "pseudocore:" + tier[0];
            assertEquals(label, chain.item(), tier[0] + " must declare its own item id");
            assertEquals(DRAGONSTEEL_ARMOR_FLOOR, chain.min(),
                    label + ": minEP must be the dragonsteel armor's floor (#27) - its "
                            + "predecessor is uncapped, so prev-max does not apply");
            assertEquals(Long.parseLong(tier[1]), chain.max(),
                    label + ": maxEP must be the uncapped sentinel");
            assertTrue(chain.evolution() == null,
                    label + " is a parallel element branch and must stay terminal");
            if (chain.gain() != null) {
                assertTrue(Pattern.compile("[0-9.]+").matcher(chain.gain()).matches(),
                        label + ": epGain must be numeric, got " + chain.gain());
            }
        }
    }

    @Test
    void beastLadderIsContinuousAndMatchesTheConfigCaps() throws IOException {
        assertLadderContinuous(BEAST);
    }

    @Test
    void dragonLadderIsContinuousAndMatchesTheConfigCaps() throws IOException {
        assertLadderContinuous(DRAGON);
    }

    /**
     * The relational guarantees: starts at 0, every minEP meets the previous
     * maxEP - except the one armor-boundary descent this curve inherits
     * (#33, {@link #DESCENT_FLOOR}) - every evolution target is the next
     * tier (absent only on the last), every maxEP is the settled curve's
     * number, epGain is numeric when present (absent = GE's 0.0, how the
     * live armor files ship), and ranges strictly ascend.
     */
    private static void assertLadderContinuous(String[][] ladder) throws IOException {
        long previousMax = 0;
        for (int i = 0; i < ladder.length; i++) {
            String expectedItem = "pseudocore:" + ladder[i][0];
            Chain chain = readChain(ladder[i][0]);

            assertEquals(expectedItem, chain.item(),
                    "file " + ladder[i][0] + " must declare its own item id");
            long expectedMin = i == 0 ? 0
                    : ladder[i][0].equals("low_magisteel_pseudocore") ? DESCENT_FLOOR
                    : previousMax;
            assertEquals(expectedMin, chain.min(),
                    expectedItem + ": minEP must meet the previous tier's maxEP"
                            + (expectedMin == DESCENT_FLOOR
                            ? " - except at the armor boundary, where it takes the jar's floor (#33)"
                            : ""));
            assertEquals(Long.parseLong(ladder[i][1]), chain.max(),
                    expectedItem + ": maxEP must match the settled armor-aligned curve (#33)");
            assertTrue(chain.min() < chain.max(),
                    expectedItem + ": the EP range must ascend");
            if (chain.gain() != null) {
                assertTrue(Pattern.compile("[0-9.]+").matcher(chain.gain()).matches(),
                        expectedItem + ": epGain must be numeric, got " + chain.gain());
            }

            if (i < ladder.length - 1) {
                assertEquals("pseudocore:" + ladder[i + 1][0], chain.evolution(),
                        expectedItem + ": must evolve into the next tier of the ladder");
            } else {
                assertTrue(chain.evolution() == null,
                        expectedItem + " is the terminal tier and must not declare an evolution - "
                                + "the wallet and belt terminals are shaped the same way");
            }
            previousMax = chain.max();
        }
    }

    private static Chain readChain(String tier) throws IOException {
        String json = Files.readString(CHAINS.resolve(tier + ".json"));
        String item = find(ITEM, json, tier, "item");
        String min = find(MIN, json, tier, "minEP");
        String max = find(MAX, json, tier, "maxEP");
        Matcher gain = GAIN.matcher(json);
        Matcher evo = EVO.matcher(json);
        return new Chain(item, Long.parseLong(min), Long.parseLong(max),
                gain.find() ? gain.group(1) : null,
                evo.find() ? evo.group(1) : null);
    }

    private static String find(Pattern pattern, String json, String tier, String field) {
        Matcher matcher = pattern.matcher(json);
        assertTrue(matcher.find(), tier + ".json must declare " + field);
        return matcher.group(1);
    }
}
