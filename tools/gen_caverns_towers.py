#!/usr/bin/env python3
"""
Generates the chaotic trial-chamber towers that stand in a caverns zone.

What Т asked for (2026-08-31, verbatim): "не колонны, а более абстрактные, очень высокие формы из
этих трайлчемберов... просто друг на друге будет стоять много хаотично сгенерированных
трайл-чемберов, которые будут друг в друге стоять... огромной высоты, достигающей 200 блоков
примерно, ну, по-разному".

How that turns into data:

  * One vanilla-pool jigsaw structure PER TIER, N tiers going up the world. Real trial chambers,
    real spawners, real vaults, real loot - Т said leave them (question 1, answered "оставить").
    The pool is minecraft:trial_chambers/chambers/end, the pool of actual chambers, NOT vanilla's
    own start pool minecraft:trial_chambers/chamber/end, which is only corridor end caps.

  * pool_aliases and spawn_overrides are copied verbatim from vanilla's trial_chambers.json.
    They are not decoration: the chamber pools reference alias pools like
    minecraft:trial_chambers/spawner/contents/melee that DO NOT EXIST as files. Drop the aliases
    and the jigsaw asks for a template pool that is not there.

  * terrain_adaptation "none", because Т ruled out encapsulate: bare rooms hanging in the open,
    no stone column around them.

  * Every tier shares one salt, spacing and separation, so their placements land on the same
    chunk and the tiers pile onto one another. Nothing in the game stops two different structures
    from occupying the same blocks - the no-overlap check only runs inside a single StructureStart
    - so overlapping start_height ranges make the rooms grow into each other, which is the "друг
    в друге стоять" part, and it is a knob rather than an accident.

  * Tiers are shifted sideways by whole chunks (OFFSETS) so the tower leans instead of stacking
    like plates.

  * Height varies per tower, decided by one hash of the lattice region (min_tiers..max_tiers in
    ZoneSpreadPlacement). Every tier of a tower asks the same region and gets the same number, so
    a tower is always a contiguous run from the bottom and never leaves a tier floating alone.

    v0.8.0 tried to do this with vanilla's `frequency` instead, on the theory that all tiers share
    a salt and so draw the same random number. That was wrong: vanilla seeds the frequency roll
    from the CHUNK coordinates, and the tiers sit on chunks that differ by their offsets, so every
    tier was rolling independently.

Run after changing anything here:
    python3 tools/gen_caverns_towers.py
"""

import json
import os
import urllib.request

MCMETA = "https://raw.githubusercontent.com/misode/mcmeta/1.21.11-data/data/minecraft/worldgen/{}.json"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "src/main/resources/data/farlands/worldgen")
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")

# ---------------------------------------------------------------- KNOBS
CAVERNS_ZONE_TYPE = 2      # index of "caverns" in ZoneGridDensityFunction.TYPE_NAMES

# ⚠ SPACING / SEPARATION / SALT / TIER_* / MIN_TIERS must match the TOWER_* constants in
# ZoneSpreadPlacement.java, which is what /farlands towers predicts from.
TIERS = 17                 # how many rooms a full-height tower can be made of
MIN_TIERS = 11             # shortest tower; height is rolled between MIN_TIERS and TIERS
TIER_STEP = 17             # blocks between one tier and the next. Chamber is ~20 tall -> overlap.
TIER_BASE_Y = -50          # centre of the bottom tier
TIER_JITTER = 4            # each tier's start height is uniform over +/- this

# Т v0.8.0: "их просто не появляется... я ожидал увидеть их МНОГО и весьма видимых". At spacing 34
# a zone held about five towers, nine of whose fifteen tiers sat under the cavern roof, so from the
# air the zone read as empty. 34 -> 18 chunks is one tower per ~288 blocks: roughly sixteen per
# average zone, and the tier stack now runs to y ~222, well clear of the terrain.
SPACING = 18               # chunks between towers (~288 blocks)
SEPARATION = 5
SALT = 8412577             # shared by every tier: that is what stacks them onto one column

POOL = "minecraft:trial_chambers/chambers/end"
SIZE = 5                   # jigsaw depth. Gets the chamber, its slices, its spawners and loot.
# 48 was too tight: a trial chamber is around 40 blocks across, so the free volume left for its
# own slices was almost nothing and every tier came out as a bare shell. Vanilla uses 116.
MAX_DISTANCE = 80

# Sideways lean, in chunks, per tier.
OFFSETS = [
    (0, 0), (1, 0), (0, 1), (1, 1), (-1, 0),
    (0, -1), (1, -1), (-1, 1), (0, 0), (1, 0),
    (-1, -1), (0, 1), (1, 1), (-1, 0), (0, -1),
    (1, 1), (-1, -1),
]



def fetch(path):
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, path.replace("/", "_") + ".json")
    if not os.path.exists(cached):
        print("downloading vanilla", path)
        urllib.request.urlretrieve(MCMETA.format(path), cached)
    with open(cached) as f:
        return json.load(f)


def write(data, subdir, name):
    directory = os.path.join(DATA, subdir)
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, name + ".json"), "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main():
    vanilla = fetch("structure/trial_chambers")

    for tier in range(TIERS):
        base = TIER_BASE_Y + tier * TIER_STEP
        structure = {
            "type": "minecraft:jigsaw",
            "biomes": "#minecraft:is_overworld",
            "step": "underground_structures",
            "terrain_adaptation": "none",
            "start_pool": POOL,
            "size": SIZE,
            "max_distance_from_center": MAX_DISTANCE,
            "use_expansion_hack": False,
            "liquid_settings": "ignore_waterlogging",
            "dimension_padding": 0,
            "start_height": {
                "type": "minecraft:uniform",
                "min_inclusive": {"absolute": base - TIER_JITTER},
                "max_inclusive": {"absolute": base + TIER_JITTER},
            },
            "pool_aliases": vanilla["pool_aliases"],
            "spawn_overrides": vanilla["spawn_overrides"],
        }
        write(structure, "structure", "caverns_tower_%d" % tier)

        offset_x, offset_z = OFFSETS[tier % len(OFFSETS)]
        placement = {
            "type": "farlands:zone_spread",
            "spacing": SPACING,
            "separation": SEPARATION,
            "salt": SALT,
            "zone_type": CAVERNS_ZONE_TYPE,
            "tier": tier,
            "min_tiers": MIN_TIERS,
            "max_tiers": TIERS,
            "offset_x": offset_x,
            "offset_z": offset_z,
        }
        write(
            {
                "placement": placement,
                "structures": [
                    {"structure": "farlands:caverns_tower_%d" % tier, "weight": 1}
                ],
            },
            "structure_set",
            "caverns_tower_%d" % tier,
        )

    top = TIER_BASE_Y + (TIERS - 1) * TIER_STEP
    short_top = TIER_BASE_Y + (MIN_TIERS - 1) * TIER_STEP
    print("wrote %d tiers, bottom y %d, top y %d..%d (towers %d..%d blocks tall)"
          % (TIERS, TIER_BASE_Y, short_top, top,
             short_top - TIER_BASE_Y + 20, top - TIER_BASE_Y + 20))


if __name__ == "__main__":
    main()
