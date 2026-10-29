package org.bitwisemadness.pseudocore.curios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bitwisemadness.pseudocore.curios.client.ClientHotkey;
import org.junit.jupiter.api.Test;

/**
 * Placeholder JUnit suite. The CI {@code junit} check asserts a non-zero test
 * count so it cannot pass vacuously, so every repo derived from this template
 * keeps at least this file (and adds real unit tests as logic grows).
 */
class PseudocoreCuriosTest {

    @Test
    void modIdIsALowercasePathSegment() {
        // MOD_ID is a compile-time constant, so referencing it does not trigger
        // class initialization - this test needs no Minecraft runtime.
        assertTrue(PseudocoreCurios.MOD_ID.matches("[a-z][a-z0-9_]{2,63}"),
                "mod id must be a lowercase resource location path segment");
    }

    @Test
    void releaseRecallKeyDefaultsToGraveAccent() {
        // DEFAULT_KEYSYM is a compile-time constant, so this reference inlines
        // the value and ClientHotkey - a client class - is never loaded; the
        // test pins the spec's keysym without needing a Minecraft runtime.
        assertEquals(96, ClientHotkey.DEFAULT_KEYSYM,
                "the release/recall hotkey must default to keysym 96 "
                        + "(GLFW_KEY_GRAVE_ACCENT, the ` key left of 1)");
    }
}
