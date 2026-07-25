#!/usr/bin/env python3
"""
Generates the noise_settings JSON for the Farlands test dimensions.

Why a script and not hand-written JSON:
vanilla's overworld noise settings are ~thousands of lines of splines. We want the NORMAL parts
of the test dimension to look exactly like the overworld, so we take vanilla's file and patch
exactly two entries in the noise_router:

  final_density -> range_choice(zone): corrupted math inside zones, untouched vanilla outside
  ridges        -> range_choice(zone): a flat marker value, so the biome lands exactly on the zone

Everything else (surface rules, aquifers, ore veins, caves) stays vanilla.

Vanilla data comes from misode/mcmeta, tag 1.21.11-data. Run this again after changing any knob:
    python3 tools/gen_noise_settings.py
"""

import json
import os
import urllib.request

MCMETA = "https://raw.githubusercontent.com/misode/mcmeta/1.21.11-data/data/minecraft/worldgen/noise_settings/{}.json"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src/main/resources/data/farlands/worldgen/noise_settings")
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")

# ---------------------------------------------------------------- KNOBS
# Everything you will actually want to tweak lives here.

# --- shape of the zones themselves (handled by our own Java density function) ---
ZONE_CELL = 4096     # world is cut into cells this big; one rectangle can live in each
ZONE_RARITY = 0.5    # chance a cell holds a zone. 0.5 = frequent, for testing. 0.12 = actually rare
ZONE_SALT = 0        # change this to shuffle which cells get picked

# --- what happens inside a zone ---
# Density delta 1.0 == roughly 134 blocks of height.
SPIRE_HEIGHT = 1.5   # ~200 blocks of needle above the base
PIT_DEPTH = 0.6      # ~80 blocks of gouge below it
BASE_SURFACE_Y = 70  # where the ground sits between the needles

# --- spires left hanging in the air, close above the terrain ---
FLOAT_BOTTOM = 110
FLOAT_TOP = 190
FLOAT_CUTOFF = 2.9   # higher = fewer floaters (the column value has to beat this)


# ---------------------------------------------------------------- density function helpers
def noise(name, xz=1.0, y=0.0):
    return {"type": "minecraft:noise", "noise": name, "xz_scale": xz, "y_scale": y}


def const(v):
    return {"type": "minecraft:constant", "argument": v}


def mul(a, b):
    return {"type": "minecraft:mul", "argument1": a, "argument2": b}


def add(a, b):
    return {"type": "minecraft:add", "argument1": a, "argument2": b}


def cube(a):
    return {"type": "minecraft:cube", "argument": a}


def absolute(a):
    return {"type": "minecraft:abs", "argument": a}


def dmax(a, b):
    return {"type": "minecraft:max", "argument1": a, "argument2": b}


def gradient(from_y, from_value, to_y, to_value):
    return {
        "type": "minecraft:y_clamped_gradient",
        "from_y": from_y,
        "to_y": to_y,
        "from_value": from_value,
        "to_value": to_value,
    }


def ridge(name):
    """
    1 - |noise|. Peaks at 1.0 exactly where the noise crosses zero, which is a thin winding line,
    and falls off sharply either side. This is what makes edges sharp instead of rounded - plain
    noise has no sharp features anywhere, no matter how hard you scale it.
    """
    return add(const(1.0), mul(absolute(noise(name)), const(-1.0)))


def needle(name_a, name_b):
    """
    Two ridge line-sets multiplied together. Each one alone gives walls; where two independent
    sets cross you get isolated points, and cubing each one first makes those points narrow.
    Result is 0..1, near zero almost everywhere, spiking to 1 at the crossings.
    """
    return mul(cube(ridge(name_a)), cube(ridge(name_b)))


def in_zone(when_in, when_out):
    """Applies `when_in` only inside a corruption rectangle. Hard edges, no blending."""
    return {
        "type": "minecraft:range_choice",
        "input": {
            "type": "farlands:zone_grid",
            "cell_size": ZONE_CELL,
            "rarity": ZONE_RARITY,
            "salt": ZONE_SALT,
        },
        "min_inclusive": 0.5,
        "max_exclusive": 10.0,
        "when_in_range": when_in,
        "when_out_of_range": when_out,
    }


# ---------------------------------------------------------------- the corrupted terrain
def corrupted_density():
    """
    Ground yanked into tall sharp needles and gouged into pits between them, plus separate
    needles left hanging in the air a short way above.
    """
    span = 320 - (-64)
    frac = (BASE_SURFACE_Y - (-64)) / span
    base = gradient(-64, 1.0, 320, round(-1.0 / frac + 1.0, 3))

    spires = mul(needle("farlands:spire_a", "farlands:spire_b"), const(SPIRE_HEIGHT))
    pits = mul(needle("farlands:pit_a", "farlands:pit_b"), const(-PIT_DEPTH))
    terrain = add(base, add(spires, pits))

    # Floating spires: the same crossing-ridge trick, but with no height gradient, so the
    # column is solid all the way through the window and cut off flat at both ends.
    columns = add(mul(needle("farlands:float_a", "farlands:float_b"), const(4.0)), const(-FLOAT_CUTOFF))
    window = add(
        gradient(FLOAT_BOTTOM - 4, -6.0, FLOAT_BOTTOM, 0.0),
        gradient(FLOAT_TOP, 0.0, FLOAT_TOP + 4, -6.0),
    )
    floating = add(columns, window)

    return dmax(terrain, floating)


def void_density():
    """test_void: nothing but levitating debris. Two sizes so it does not read as uniform."""
    big = add(
        mul(cube(cube(noise("farlands:corruption_mask", 1.0, 0.6))), const(3.0)),
        const(-0.9),
    )
    small = add(
        mul(cube(cube(noise("farlands:float_shape", 1.0, 1.0))), const(2.5)),
        const(-0.45),
    )
    return dmax(big, small)


# ---------------------------------------------------------------- build
def fetch(name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name + ".json")
    if not os.path.exists(path):
        print("downloading vanilla", name)
        urllib.request.urlretrieve(MCMETA.format(name), path)
    with open(path) as f:
        return json.load(f)


def main():
    os.makedirs(OUT, exist_ok=True)

    # --- test_zones: vanilla overworld with corruption patched into two router entries
    zones = fetch("overworld")
    router = zones["noise_router"]
    router["final_density"] = in_zone(corrupted_density(), router["final_density"])
    # A flat marker instead of vanilla weirdness: 1.0 inside a zone, -1.0 outside. The biome
    # list in dimension/test_zones.json splits on exactly this, so biome == zone, always.
    router["ridges"] = in_zone(const(1.0), const(-1.0))
    write(zones, "test_zones")

    # --- test_void: vanilla end, terrain replaced wholesale
    void = fetch("end")
    void["noise_router"]["final_density"] = void_density()
    write(void, "test_void")


def write(data, name):
    path = os.path.join(OUT, name + ".json")
    with open(path, "w") as f:
        json.dump(data, f, indent=1)
        f.write("\n")
    print("wrote", os.path.relpath(path, ROOT), "(%.0f KB)" % (os.path.getsize(path) / 1024))


if __name__ == "__main__":
    main()
