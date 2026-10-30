# Pseudocore Curios

The companion addon to [Pseudocore](https://github.com/HexaHexes/pseudocore).
Keep a core on your belt through Curios, press a key to release its stored mob at
your position, and press it again to recall that mob through the normal capture
gates. The addon also carries the Pseudocore ecosystem's Tensura-side data
(Gear Evolution chains covering every core tier), so Tensura and Gear Evolution
integration lives here rather than in base Pseudocore, mirroring how the wallet
chains ship in lightmans-tensura rather than in Lightman's Currency.

## What it does

- A Curios slot that accepts Pseudocore cores (beast and dragon lines).
- A rebindable hotkey (default the `` ` `` key) that releases the core's stored
  mob at the player's position, and recalls it again. Recall reuses Pseudocore's
  capture paths, so every gate (ownership, tier limits, blacklist) re-applies.
  Right-clicking air with a core in hand does the same press; sneak-right-click
  clears it (`release` by default, a confirmed `destroy` on request).
- Death-preserve (off by default): a tracked mob about to die to a configured
  damage source is saved into the core that released it instead - searched
  slot -> inventory -> corpse, capture gates first, recorded as `dead_mob`.
- Gear Evolution `addon_chains` data for all seventeen core tiers, following
  the same setup Lightman's wallets and Traveler Tool Belts already use.

## Dependencies

| | Mod | Why |
|---|---|---|
| Required | Minecraft | - |
| Required | NeoForge | - |
| Required | Pseudocore | Cores, `releaseContained`, `tryCapture`, `TensuraBridge` - the addon calls, it never reimplements |
| Required | Curios | The slot itself |
| Optional | Tensura: Reincarnated | Chain data is inert JSON without its ecosystem; the code paths gate through Pseudocore's `TensuraBridge` |
| Optional | Gear Evolution | Reads the addon_chains data; without it the files sit unread |
| Optional | Corpse | One death-save search leg (the corpse inventory); gated by mod presence, absent = that leg is skipped |
| Optional | Xaero's Minimap | Waypoint drop on unreachable recall; client-only, absent = no waypoint |

## Configuration

Server config: `config/pseudocore-curios-server.toml` (gameplay rules a
server owner sets; nothing here is a client preference):

| Key | Default | Meaning |
|---|---|---|
| `deathSave.enabled` | `false` | Master switch: may a dying tracked mob be preserved into its core instead? |
| `deathSave.damageSources` | `minecraft:mob_attack`, `minecraft:player_attack` | Damage types that may trigger preservation; everything else (void included) kills by omission |
| `deathSave.allowNonSlotCores` | `false` | Widen the core *search* beyond the Curios slot to the main inventory; linkage identity stays absolute either way |
| `deathSave.searchCorpses` | `true` | Also search the owner's Corpse inventory (no-op without Corpse) |
| `filledCoreClearAction` | `release` | What sneak-right-click does to a filled core: `release` (spawn at your feet) or `destroy` (deleted after a second press within 5 s) |

Pseudocore resolves from this org's GitHub Packages maven (it is not published
to CurseForge or Modrinth), so CI needs the workflow token (already wired in
`ci.yml`) and a local build needs `gpr.user` / `gpr.key` in
`~/.gradle/gradle.properties`.

## Credits

- Built on [NeoForge](https://neoforged.net/).
- Derived from [neoforge-mod-template](https://github.com/HexaHexes/neoforge-mod-template).

## License

GNU Lesser General Public License v2.1 - see [`LICENSE`](LICENSE).
Copyright (c) 2026 Bitwise Madness.

## AI disclosure

Parts of this project were written with the assistance of AI coding tools.
AI-assisted code, data and documentation ship only after human review by the
maintainers.
