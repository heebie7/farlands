# Farlands

Minecraft 1.21.11 Fabric mod. Rare corrupted zones with broken terrain generation, inspired by
the classic Farlands bug.

It ships two test dimensions used for tuning what a corrupted zone actually looks like. The real
target (zones scattered through the overworld via TerraBlender) comes after the terrain looks
right.

**Three zone types**, picked per zone by the same hash that places it:

| Type | What it is |
|---|---|
| `spires` | Ground yanked into tall sharp needles, gouged into pits between them, plus needles left hanging in the air |
| `classic` | Solid mass from bedrock to the height limit, carved into Swiss cheese by noise that ignores Z, so tunnels run along one axis |
| `caverns` | Tier after tier of enormous open cave the whole way up, thin floors between them, no roof at all on the top tier. Shafts drop through every tier, through the bedrock, into real void. Towers of trial chambers stand in it, room piled on room, 150-250 blocks tall |

`/farlands locate [spires|classic|caverns]` points at the nearest one and prints a `/tp` for it.

## Trying it

Build lands in the `latest` release:
`https://github.com/heebie7/farlands/releases/download/latest/farlands-0.1.0.jar`

Drop it in `mods/` next to Fabric API, make a **new** world, then:

```
/execute in farlands:test_zones run tp @s ~ 120 ~
/execute in farlands:test_void run tp @s ~ 80 ~
```

Creative + fly. Corrupted zones are frequent on purpose right now — walk a few hundred blocks
and you should hit one.

### test_zones

Vanilla overworld terrain, except inside corrupted zones. Ordinary earth and stone, grass and
trees on top — the broken shapes *are* the terrain, not decoration.

Since v0.6.0 zones keep the vanilla biome the terrain lands in: a zone in a desert is sand, in
ice spikes it is ice. Only `final_density` is patched.

### test_void

Placeholder for the void dimension. Nothing but levitating debris in two sizes. The "fall 500
blocks and get moved here" part is not built yet.

## Tuning

Most knobs live at the top of [`tools/gen_noise_settings.py`](tools/gen_noise_settings.py):

| Knob | What it does |
|---|---|
| `ZONE_CELL` | Cell the world is cut into; one rectangle lives in each. Rectangle comes out ~1/4 to ~3/4 of this |
| `ZONE_RARITY` | Chance a cell holds a zone. `0.5` = frequent (testing), `0.12` = actually rare |
| `ZONE_SALT` | Shuffles which cells get picked |
| `SPIRE_HEIGHT` | Needle height. `1.5` ≈ 200 blocks |
| `PIT_DEPTH` | How deep it gouges between the needles |
| `BASE_SURFACE_Y` | Where the ground sits between needles |
| `FLOAT_BOTTOM` / `FLOAT_TOP` / `FLOAT_CUTOFF` | Height band and rarity of the floating needles |
| `CAVERNS_PERIOD` / `CAVERNS_THICKNESS` | Blocks between caverns floors, and how thick a floor is. Thickness under ~12 aliases against the 8-block noise grid |
| `CAVERNS_TOP_Y` / `CAVERNS_OPEN_SPAN` | Where the caverns roof stops existing, and over how many blocks it fades |
| `CAVERNS_WOBBLE_AMP` / `CAVERNS_DETAIL_AMP` | How far the floors are warped off flat, long wave and short |
| `CAVERNS_PIT_*` | Caverns shafts. **Must match the constants in `VoidShaftClear.java`**, which removes the bedrock and the aquifer water inside them |

Towers have their own generator: [`tools/gen_caverns_towers.py`](tools/gen_caverns_towers.py) —
tier count, tier spacing, jigsaw depth, sideways lean, and the per-tier frequency taper that
gives towers different heights.

Needle spacing is not in that file — it is `firstOctave` in
`worldgen/noise/spire_a.json` and `spire_b.json`. More negative = further apart.

Water thinning is configured in
`worldgen/configured_feature/water_thinner.json`: `chance` (share of water *bodies* removed —
one roll per connected body, it goes whole or stays whole), `min_y` (nothing at or below this is
touched, so oceans and rivers survive), `max_y`, `max_body_size` (bigger bodies are left alone).

Since v0.6.0 the zones sit in ordinary vanilla biomes, so the feature is attached to **every**
overworld biome from Java (`BiomeModifications`) and tests "am I inside a zone" itself, using the
same hash the terrain mask runs on. The zone knobs it reads are the constants at the top of
`ZoneGridDensityFunction.java` — those must be kept equal to `ZONE_CELL` / `ZONE_RARITY` /
`ZONE_SALT` / `ZONE_TYPES` in the script.

After changing anything in the script: `python3 tools/gen_noise_settings.py`, then push.
JSON files under `data/` take effect on the next build with no regeneration.

### Faster loop than pushing

Building goes through GitHub Actions (no Java on this Mac), which takes minutes per number
tweaked. Data-only changes can skip it: copy `src/main/resources/` into
`<world>/datapacks/farlands/` (with a `pack.mcmeta` next to it) and make a new world. Java
changes still need a build.

## How it works

`noise_settings/test_zones.json` is vanilla's overworld file with exactly two entries of the
`noise_router` patched:

- `final_density` — terrain shape. Wrapped in `range_choice` on the zone mask: our math inside
  a zone, untouched vanilla outside.
`ridges` used to be patched too, to force our own biome onto the zone. Dropped in v0.6.0:
zones take the vanilla biome instead.

The mask itself is `farlands:zone_grid`, our own density function type in Java. Vanilla density
functions cannot see raw X/Z, so every mask built out of them follows noise contours and comes
out as blobs; straight walls need the block coordinates.

Terrain sharpness comes from crossing ridged noise (`1 - |noise|`), not from scaling ordinary
noise — plain noise has no sharp features at any amplitude. One ridge set gives walls; two
independent sets multiplied give isolated needles.

That file is generated, not written by hand — vanilla's is thousands of lines of splines, and
we want the normal parts of the world to stay exactly normal. Regenerate with the script.

## Version traps

Minecraft 1.21.11 is not what the wiki documents (it is on 26.x now). Things that differ:

- The router entry is `preliminary_surface_level`, **not** `initial_density_without_jaggedness`.
- Density functions `sub`, `div`, `lerp`, `ceil`, `floor`, `round`, `truncate`, `negate` do not
  exist here — they arrived in 26.3. Subtract with `add` + `mul` by −1.
- `invert` still has its old name (`reciprocal` is 26.3).
- On misode.github.io, set the version in the header or it generates JSON we cannot load.

## Structures gated to a zone

Structures are normally kept in place by their biome list, and our zones have no biome of their
own. So the caverns towers use `farlands:zone_spread`, a `StructurePlacement` that wraps a real
`random_spread` and asks `ZoneGridDensityFunction.zoneTypeAt` first — the same function the
terrain mask, `/farlands locate` and both features already use.

Every tier of a tower shares one salt, so their placements land on the same chunk and the rooms
pile up. Nothing in the game stops two different structures from occupying the same blocks: the
no-overlap check only runs inside a single `StructureStart`. Overlapping `start_height` ranges
are therefore what makes rooms grow into each other, on purpose.

`pool_aliases` is copied verbatim from vanilla `trial_chambers.json` into every tier. It is not
decoration — the chamber pools reference alias pools such as
`minecraft:trial_chambers/spawner/contents/melee` that do not exist as files.
