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

  * Height varies because the upper tiers are given frequency < 1. All tiers share a salt, so the
    frequency roll draws the SAME number for each of them: a tower keeps every tier whose
    frequency beats that number, which is always a run starting from the bottom. Towers come out
    between roughly 150 and 250 blocks tall and never leave a tier floating on its own.

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

TIERS = 15                 # how many rooms a full-height tower is made of
TIER_STEP = 17             # blocks between one tier and the next. Chamber is ~20 tall -> overlap.
TIER_BASE_Y = -48          # centre of the bottom tier
TIER_JITTER = 4            # each tier's start height is uniform over +/- this

SPACING = 34               # chunks between towers, same as vanilla trial chambers (~544 blocks)
SEPARATION = 12
SALT = 8412577             # shared by every tier: that is what stacks them onto one column

POOL = "minecraft:trial_chambers/chambers/end"
SIZE = 4                   # jigsaw depth. 4 gets the chamber, its slices, its spawners and loot.
MAX_DISTANCE = 48          # keeps a tier from sprawling sideways into a complex

# Sideways lean, in chunks, per tier.
OFFSETS = [
    (0, 0), (1, 0), (0, 1), (1, 1), (-1, 0),
    (0, -1), (1, -1), (-1, 1), (0, 0), (1, 0),
    (-1, -1), (0, 1), (1, 1), (-1, 0), (0, -1),
]

# Chance a tier exists at all. Monotonically falling, so towers are cut off at the top.
FREQUENCIES = [
    1.0, 1.0, 1.0, 1.0, 1.0,
    1.0, 1.0, 1.0, 1.0, 0.92,
    0.84, 0.74, 0.62, 0.5, 0.38,
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
            "frequency": FREQUENCIES[tier % len(FREQUENCIES)],
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
    print("wrote %d tiers, y %d..%d (tower up to ~%d blocks tall)"
          % (TIERS, TIER_BASE_Y, top, top - TIER_BASE_Y + 20))


if __name__ == "__main__":
    main()
