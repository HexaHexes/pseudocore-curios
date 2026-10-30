package org.bitwisemadness.pseudocore.curios.hotkey;

/**
 * What a clear gesture does to a FILLED core (#15's
 * {@code filledCoreClearAction}).
 *
 * <p>Pure JDK on purpose: the invalid-value fallback the issue specifies
 * lives in {@link #parse}, testable by junit without loading a config
 * spec or any Minecraft class.</p>
 */
public enum ClearActionMode {

    /** Spawn the stored mob at the player's feet - the shared release path. */
    RELEASE,

    /** Delete the stored NBT, behind a two-step confirm - no mob spawns. */
    DESTROY;

    /**
     * Resolve a raw config value: release/destroy case-insensitively,
     * anything else (absent, typo'd, hand-edited) falls back to
     * {@link #RELEASE} - a stray gesture must never become the destructive
     * path by accident.
     */
    public static ClearActionMode parse(String raw) {
        if ("destroy".equalsIgnoreCase(raw)) {
            return DESTROY;
        }
        return RELEASE;
    }
}
