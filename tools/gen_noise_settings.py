#!/usr/bin/env python3
"""
Generates the noise_settings JSON for the Farlands test dimensions.

Why a script and not hand-written JSON:
vanilla's overworld noise settings are ~thousands of lines of splines. We want the NORMAL parts
of the test dimension to look exactly like the overworld, so we take vanilla's file and patch
exactly two entries in the noise_router:

  final_density -> range_choice(mask): corrupted math inside zones, untouched vanilla outside
  ridges        -> range_choice(mask): a flat marker value, so the biome lands exactly on the zone

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

# How much of the world is corrupted. The mask noise runs roughly -1..1.
# 0.20 = frequent, good for testing (walk a few hundred blocks and you hit one).
# 0.55+ = actually rare, which is what the finished mod wants.
ZONE_THRESHOLD = 0.20

# Height of the spires. Density delta 1.0 == roughly 134 blocks of height here.
SPIRE_AMPLITUDE = 0.9      # ~120 blocks up and down
ZONE_ROUGHNESS = 0.15      # ~20 blocks of extra chaos on top
BASE_SURFACE_Y = 70        # where the corrupted ground sits when noise is neutral

# Floating chunks with no support. Higher CUTOFF = rarer.
FLOAT_STRENGTH = 2.2
FLOAT_CUTOFF = 0.5


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


def in_zone(when_in, when_out):
    """Applies `when_in` only where the corruption mask is above the threshold."""
    return {
        "type": "minecraft:range_choice",
        "input": noise("farlands:corruption_mask", 1.0, 0.0),
        "min_inclusive": ZONE_THRESHOLD,
        "max_exclusive": 10.0,
        "when_in_range": when_in,
        "when_out_of_range": when_out,
    }


# ---------------------------------------------------------------- the corrupted terrain
def corrupted_density():
    """
    Ground that got yanked upward and shoved downward: dense spires of ordinary earth,
    packed close, with sharp unpredictable transitions. Plus a few chunks left floating.
    """
    # Straight line from solid at the bottom to empty at the top; crosses zero at BASE_SURFACE_Y.
    span = 320 - (-64)
    frac = (BASE_SURFACE_Y - (-64)) / span
    top_value = -1.0 / frac + 1.0
    base = gradient(-64, 1.0, 320, round(top_value, 3))

    # cube() keeps the sign but pushes middling values toward zero -> flats stay flat,
    # peaks get sharp and needle-like. Sign is kept, so it digs pits as well as raising spires.
    spires = mul(cube(noise("farlands:spire_shape", 1.0, 0.0)), const(SPIRE_AMPLITUDE))
    rough = mul(noise("farlands:zone_rough", 1.0, 0.0), const(ZONE_ROUGHNESS))
    terrain = add(base, add(spires, rough))

    # Double cube = only the most extreme noise survives, so these are isolated lumps
    # hanging in the air rather than a second ceiling.
    floating = add(
        mul(cube(cube(noise("farlands:float_shape", 1.0, 1.0))), const(FLOAT_STRENGTH)),
        const(-FLOAT_CUTOFF),
    )
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
