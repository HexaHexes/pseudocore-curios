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

**Test:** `./gradlew runGameTestServer` - `PseudocoreCuriosGameTests` (24 tests:
smoke, the Gear Evolution NBT-retention gate, the linkage stamp/tag/record,
the unload position refresh, the linkage cap/forget, the Curios slot
accept/reject pair, the hotkey refusal/release pair, the recall quartet
(capture, unreachable, wrong-core, anti-theft) with the waypoint payload
folded in, the air right-click release/recall pair, the upgrade-stamp
cross-repo round trip, the death-preserve quintet, the clear-gesture
quartet (release default, destroy confirm, instant death-wipe,
silent-and-free no-op), and the dragon-core air-press gate (#48)).
`expectedTests = 24` in
`build.gradle` must match the `@GameTest` count.
Game-test dependencies are staged into `run/mods` by `prepareGametestMods`
from the `gametestMods` configuration - which carries **Ice and Fire CE
(+ Jupiter, Uranus)** so the dragon line registers in the test world
(#48; headless IaF is proven by pseudocore's own CI).

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
  inner one). **The wedge also happens solo** (twice locally, once on CI,
  with no second run anywhere): tests start, no failure is ever reported,
  the batch line repeats until the outer timeout kills the job - the
  standing remedy is a re-run, which has always passed; do not go hunting
  inside test code for a phantom failure when the log shows zero
  `failed at` lines.
- Asserting a mob **died** vs was **captured** needs different evidence:
  a naturally dead mob lingers in storage for its ~20-tick death
  animation (`isDeadOrDying()`, not `getEntity(...) == null`), while
  capture's `discard()` is immediate (absence by UUID is fair). And with
  every player sharing the world spawn, *any* area scan must be replaced
  by a UUID lookup - `releaseContained` restores the stored UUID, which
  makes `level.getEntity(mob.getUUID())` both precise and contamination-
  proof (an area scan once let one test's discard starve another's
  recall).
- Every mock player is placed at the world spawn, so concurrent tests share
  one activity area: entity assertions must resolve **by the record's
  UUID** (`level.getEntity(record.mobUuid())`), never by an AABB scan plus
  `get(0)` - a scan picks up sibling tests' released mobs (proven: one
  test's discard-by-scan starved another test's recall of its mob). For the
  same reason the mock connection's ad-hoc channel whitelist needs this
  addon's own clientbound payload ids added (`unreachable_recall`) - any
  `sendToPlayer` of an unlisted payload throws
  `Payload ... may not be sent to the client`.
- The pseudocore pin carries tamed-only capture (#111, alpha.7+):
  `mockPlayer` pins `tamedMobsOnly` off for copper/iron once, suite-wide,
  never restored - a flip-flop across parallel tests would race (each
  gametest body is atomic within its tick, so an idempotent one-way pin is
  safe and a restore is not). The same one-way rule governs the
  `deathSave*` flips: each death test flips ON and restores OFF **inside
  one synchronous body**, and only the HAS_DEAD press hop runs after the
  restore (the press never reads the switch).
- One player, one throttle: `ReleaseRecallAction.checkCooldown`/`LAST_USE`
  is shared by the hotkey, the air click and the clear gesture (#11/#34/#15)
  - press-and-remember in one place, so no trigger can bypass or desync the
  window, and a `destroy` confirm cannot dodge it either (its 5 s window
  deliberately outlasts the cooldown).
- **Never gate on `instanceof PseudocoreItem` for "is this a core"**: the
  dragon line extends IaF's horn item instead (#48 shipped exactly this
  bug in the air listener - the whole dragon line silently skipped the
  press family). `ReleaseRecallAction.isCore` is the namespace-based
  predicate; use it everywhere, including new dispatch points. The two
  capture dispatchers (`recall`, `DeathPreserveEvents`) are the exception
  that proves the rule: they use the instanceof on purpose, to pick the
  beast-vs-dragon CAPTURE path (dragon cores fall into the
  `tryCaptureDragon` branch by not matching).
- **Every mod that pushes clientbound payloads to tracking players must be
  whitelisted in the mock connection's ad-hoc channel set** (`mockPlayer`,
  `ChannelAttributes.getOrCreateAdHocChannels`): the stub connection never
  ran the login handshake, so `NetworkRegistry.checkPacket` rejects any
  unlisted payload. A mod that CATCHES its rejected sends only stack-traces
  (Curios, ManasCore, Tensura - noisy, whitelisted for readable logs); one
  that does NOT catch them crashes the whole suite - IaF CE's
  `neoforge:sync_attachments` is exactly that (found by staging IaF for the
  dragon line, #48). When a new optional dep joins `gametestMods`, check
  whether it syncs payloads per tick and extend the whitelist in the same
  change.
- `corpse` and `xaeros-minimap` are compileOnly optional deps referenced
  only behind `ModList` gates at runtime (Xaero only client-side) - the
  same class-load discipline as the IaF references in base pseudocore.
  The sharp edge: FML's automatic-subscriber scan calls
  `getDeclaredMethods()` on `@EventBusSubscriber` classes, which resolves
  every synthetic lambda parameter eagerly - a listener-class lambda
  taking `CorpseEntity` made mod construction throw `NoClassDefFoundError`
  at startup whenever Corpse was absent (proven live). Optional-dep types
  therefore go in **non-scanned helper classes** (no annotation, vanilla
  only method signatures) called behind the gate; see
  `death/CorpseSearch.java`.
- The mixin config is registered but empty; any mixin added here must also set
  the `package` field in `pseudocore_curios.mixins.json`.
- Capture/recall deliberately reuse Pseudocore's `tryCapture` /
  `interactLivingEntity` rather than bypassing them - a "convenience" shortcut
  would skip the anti-theft rule and tier gates.
- The Gear Evolution retention risk (does a captured mob survive an evolution
  swap?) is tracked in this repo's issues; the chain data ships first because
  it is inert without Gear Evolution, while the NBT test needs GE on the
  gametest classpath.
