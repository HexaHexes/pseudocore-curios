package org.bitwisemadness.pseudocore.curios;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bitwisemadness.pseudocore.curios.hotkey.ClearActionMode;
import org.junit.jupiter.api.Test;

/**
 * #15's config parsing: the documented values resolve, and every invalid
 * value falls back to RELEASE - the destructive path is only ever reached
 * by an explicit, valid config. Pure JDK: the parser lives in
 * {@link ClearActionMode} precisely so this can be a plain junit test with
 * no Minecraft runtime.
 */
class ClearActionModeTest {

    @Test
    void parsesTheDocumentedValues() {
        assertEquals(ClearActionMode.RELEASE, ClearActionMode.parse("release"));
        assertEquals(ClearActionMode.DESTROY, ClearActionMode.parse("destroy"));
        assertEquals(ClearActionMode.DESTROY, ClearActionMode.parse("DESTROY"),
                "the mode is case-insensitive like every other value in this mod");
    }

    @Test
    void anythingInvalidFallsBackToRelease() {
        assertEquals(ClearActionMode.RELEASE, ClearActionMode.parse("banana"),
                "an unknown mode must never become the destructive path");
        assertEquals(ClearActionMode.RELEASE, ClearActionMode.parse(""));
        assertEquals(ClearActionMode.RELEASE, ClearActionMode.parse(null));
        assertEquals(ClearActionMode.RELEASE, ClearActionMode.parse("reLeAsE"));
    }
}
