# Repo rules - pseudocore-curios

Repo-specific rules and notes. The org-wide `AGENTS.md` beside this file is
maintained in [`neoforge-mod-template`](https://github.com/HexaHexes/neoforge-mod-template)
and pulled in verbatim - **never hand-edit it.** Everything in this file is this
repo's own and is edited freely; keep it aligned with the org rules rather than
restating them.

**Repo:** `HexaHexes/pseudocore-curios` - **Mod ID:** `pseudocore_curios` - **Branch:** `main`
**Platform:** Minecraft 1.21.1 / NeoForge 21.1.259 / JDK 21
**Jar:** `build/libs/pseudocore_curios-<version>.jar`

The Curios-slot companion to Pseudocore: a slot that holds a core, a rebindable
hotkey that releases the stored mob at the player and recalls it through the
normal capture paths, and the Tensura-ecosystem data for cores (Gear Evolution
`addon_chains` for every tier). By pack decision this repo - not base
Pseudocore - carries all Tensura/Gear Evolution integration, mirroring how
wallet chains ship in lightmans-tensura.

**Dependencies**
- Required: Minecraft, NeoForge, Pseudocore, Curios.
- Pseudocore resolves from `maven.pkg.github.com/HexaHexes/pseudocore` - it is
  not on CurseForge or Modrinth (its alphas are GitHub-only), so CI passes
  `GITHUB_TOKEN` with `packages: read` and a local build needs `gpr.user` /
  `gpr.key`. Curios, Gear Evolution and the Tensura closure resolve from
  CurseMaven, pinned by `*_file_id` in `gradle.properties`.
- Optional: Tensura: Reincarnated and Gear Evolution - the chain JSONs are
  inert without their reader; code paths gate through Pseudocore's
  `TensuraBridge`, never by importing Tensura types.

**Test:** `./gradlew runGameTestServer` - `PseudocoreCuriosGameTests` (2 tests:
smoke plus the Gear Evolution NBT-retention gate). `expectedTests = 2` in
`build.gradle` must match the `@GameTest` count.
Game-test dependencies are staged into `run/mods` by `prepareGametestMods`
from the `gametestMods` configuration.

**Gotchas**
- `mod_id` contains underscores while the Java package is
  `org.bitwisemadness.pseudocore.curios` - do not "fix" one to match the other;
  the checklist in the template README treats them as separate fields.
- Run game-test gates **serially**. Two concurrent `runGameTestServer` runs
  share `run/` (logs, configs, mods staging) and collide on file locks; a
  wedged server from one run silently blocks the other. Long-running async
  tests also need `timeoutTicks` on their `@GameTest` - a test awaiting a
  delayed callback never times out on its own and holds the batch open
  forever (CI's `timeout-minutes` is the outer belt, the annotation is the
  inner one).
- The mixin config is registered but empty; any mixin added here must also set
  the `package` field in `pseudocore_curios.mixins.json`.
- Capture/recall deliberately reuse Pseudocore's `tryCapture` /
  `interactLivingEntity` rather than bypassing them - a "convenience" shortcut
  would skip the anti-theft rule and tier gates.
- The Gear Evolution retention risk (does a captured mob survive an evolution
  swap?) is tracked in this repo's issues; the chain data ships first because
  it is inert without Gear Evolution, while the NBT test needs GE on the
  gametest classpath.
