package org.bitwisemadness.pseudocore.curios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Death-preserve (#14) file-based checks, in the style of the org's other
 * data suites: the junit source set has no Minecraft runtime here, so what
 * pins cleanly from disk is what ships on disk (the message surface) plus
 * the two load-bearing source properties whose violation would compile and
 * pass every game test while still breaking in play.
 */
class DeathPreserveFileTest {

    private static final Path LANG =
            Path.of("src/main/resources/assets/pseudocore_curios/lang/en_us.json");

    private static final Path EVENTS = Path.of(
            "src/main/java/org/bitwisemadness/pseudocore/curios/death/DeathPreserveEvents.java");

    private static final Path CLIENT = Path.of(
            "src/main/java/org/bitwisemadness/pseudocore/curios/client/DeathCoreClient.java");

    @Test
    void shipsEveryDeathPreserveMessage() throws IOException {
        String lang = Files.readString(LANG);
        assertTrue(lang.contains("\"pseudocore_curios.message.preserved\""),
                "the preservation announcement must ship");
        assertTrue(lang.contains("\"pseudocore_curios.message.fell\""),
                "the fall announcement must ship");
        assertTrue(lang.contains("\"pseudocore_curios.message.has_dead\""),
                "the press's dead-state report must ship");
        assertTrue(lang.contains("\"pseudocore_curios.tooltip.preserved\""),
                "the death tooltip must ship");
        // The tooltip takes two arguments (entity description, cause) - the
        // client composes them; a key with the wrong arity renders raw.
        assertTrue(lang.contains("%s - preserved after dying to a %s."),
                "the tooltip key must keep both placeholders in order");
    }

    @Test
    void captureComesBeforeMigrationAndTheTintDelegates() throws IOException {
        String events = Files.readString(EVENTS);
        int capture = events.indexOf("PseudocoreItem.tryCapture");
        int migrate = events.indexOf("migrateToDeathRecord(core");
        assertTrue(capture >= 0 && migrate > capture,
                "the real capture must run BEFORE the migration - gates first, "
                        + "storage second (#14's locked order); found capture=" + capture
                        + " migrate=" + migrate);

        String client = Files.readString(CLIENT);
        assertTrue(client.contains("OrbColors.orbColorFor"),
                "the dead tint must come from the shared ladder, not a copy");
        assertTrue(client.contains("PseudocoreTintHandler.TINT"),
                "the live path must be delegated, not reimplemented");
        assertTrue(client.contains("ModDataComponents.CONTAINED_MOB"),
                "the tooltip and tint must both hide behind a live mob");
    }
}
