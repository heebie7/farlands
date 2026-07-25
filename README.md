# Farlands

Minecraft 1.21.11 Fabric mod. Rare corrupted zones with broken terrain generation, inspired by
the classic Farlands bug.

**v0.1.0 is data-only** — no Java yet. It ships two test dimensions used for tuning what a
corrupted zone actually looks like. The real target (zones scattered through the overworld via
TerraBlender) comes after the terrain looks right.

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

Vanilla overworld terrain, except inside corrupted zones, where the ground gets yanked into
packed spires and slammed into pits. Ordinary earth and stone, grass and trees on top — the
spires *are* the terrain, not decoration.

The zone and the biome (`farlands:spires`) always line up exactly: the same mask noise drives
both terrain shape and biome choice.

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

Needle spacing is not in that file — it is `firstOctave` in
`worldgen/noise/spire_a.json` and `spire_b.json`. More negative = further apart.

Water thinning is configured in
`worldgen/configured_feature/water_thinner.json`: `chance` (share of columns cleared),
`min_y` (nothing at or below this is touched, so oceans and rivers survive), `max_y`.

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
- `ridges` — biome placement only, never terrain. Forced to a flat `1.0` inside zones and
  `-1.0` outside, and `dimension/test_zones.json` splits its two biomes on exactly that.

Terrain and biome cannot drift apart, because both read the same mask.

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
