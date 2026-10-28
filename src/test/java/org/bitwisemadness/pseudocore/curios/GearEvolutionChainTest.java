package org.bitwisemadness.pseudocore.curios;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <p>The EP caps are pinned to Pseudocore's {@code PseudocoreConfig} tables
 * (copper 1,500 through adamantite 1,500,000; dragon copper 150,000 through
 * gold 825,000) so a config rebalance that forgets the chain fails here. The
 * two uncapped terminal tiers (config cap 0) carry a {@link #UNCAPPED_SENTINEL}
 * instead - GE needs a number even for a tier that must never cap out.</p>
 */
class GearEvolutionChainTest {

    /** The terminal tiers' maxEP: uncapped in config, a ceiling GE can hold. */
    private static final long UNCAPPED_SENTINEL = 100_000_000L;

    private static final Path CHAINS =
            Path.of("src/main/resources/data/pseudocore_curios/addon_chains");

    /** PseudocoreConfig.TIERS order: key plus default EP cap. */
    private static final String[][] BEAST = {
            {"copper_pseudocore", "1500"},
            {"iron_pseudocore", "7500"},
            {"silver_pseudocore", "15000"},
            {"gold_pseudocore", "37500"},
            {"netherite_pseudocore", "112500"},
            {"low_magisteel_pseudocore", "150000"},
            {"high_magisteel_pseudocore", "225000"},
            {"pure_magisteel_pseudocore", "300000"},
            {"mithril_pseudocore", "450000"},
            {"orichalcum_pseudocore", "750000"},
            {"adamantite_pseudocore", "1500000"},
            {"hihiirokane_pseudocore", String.valueOf(UNCAPPED_SENTINEL)},
    };

    /** PseudocoreConfig.DRAGON_TIERS order: key plus default EP cap. */
    private static final String[][] DRAGON = {
            {"copper_dragon_pseudocore", "150000"},
            {"iron_dragon_pseudocore", "300000"},
            {"silver_dragon_pseudocore", "525000"},
            {"gold_dragon_pseudocore", "825000"},
            {"netherite_dragon_pseudocore", String.valueOf(UNCAPPED_SENTINEL)},
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
        assertEquals(BEAST.length + DRAGON.length, shipped.size(),
                "both ladders must ship in full - a missing tier stalls evolution mid-ladder");
        for (String[][] ladder : new String[][][]{BEAST, DRAGON}) {
            for (String[] tier : ladder) {
                assertTrue(shipped.contains(tier[0] + ".json"),
                        "missing chain file for tier: " + tier[0]);
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
     * maxEP, every evolution target is the next tier (absent only on the last),
     * EP caps equal the config table, and ranges strictly ascend.
     */
    private static void assertLadderContinuous(String[][] ladder) throws IOException {
        long previousMax = 0;
        for (int i = 0; i < ladder.length; i++) {
            String expectedItem = "pseudocore:" + ladder[i][0];
            Chain chain = readChain(ladder[i][0]);

            assertEquals(expectedItem, chain.item(),
                    "file " + ladder[i][0] + " must declare its own item id");
            assertEquals(i == 0 ? 0 : previousMax, chain.min(),
                    expectedItem + ": minEP must meet the previous tier's maxEP");
            assertEquals(Long.parseLong(ladder[i][1]), chain.max(),
                    expectedItem + ": maxEP must match PseudocoreConfig's default EP cap");
            assertTrue(chain.min() < chain.max(),
                    expectedItem + ": the EP range must ascend");
            assertTrue(Pattern.compile("[0-9.]+").matcher(chain.gain()).matches(),
                    expectedItem + ": epGain must be numeric, got " + chain.gain());

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
        String gain = find(GAIN, json, tier, "epGain");
        Matcher evo = EVO.matcher(json);
        return new Chain(item, Long.parseLong(min), Long.parseLong(max), gain,
                evo.find() ? evo.group(1) : null);
    }

    private static String find(Pattern pattern, String json, String tier, String field) {
        Matcher matcher = pattern.matcher(json);
        assertTrue(matcher.find(), tier + ".json must declare " + field);
        return matcher.group(1);
    }
}
