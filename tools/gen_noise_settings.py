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
ZONE_CELL = 3000     # world is cut into cells this big; one rectangle can live in each
ZONE_RARITY = 0.1    # chance a cell holds a zone. 0.1 = 5x rarer than testing default
ZONE_SALT = 0        # change this to shuffle which cells get picked
ZONE_TYPES = 3       # how many zone types (1=spires, 2=+classic, 3=+caverns)

# --- what happens inside a SPIRES zone ---
# Density delta 1.0 == roughly 134 blocks of height.
SPIRE_HEIGHT = 1.5   # ~200 blocks of needle above the base
PIT_DEPTH = 0.6      # ~80 blocks of gouge below it
BASE_SURFACE_Y = 70  # where the ground sits between the needles

# --- spires left hanging in the air, close above the terrain ---
FLOAT_BOTTOM = 110
FLOAT_TOP = 190
FLOAT_CUTOFF = 1.8   # higher = fewer floaters (the column value has to beat this)

# --- spire shape ---
SPIRE_THICKNESS = 0.07  # offset added to ridge before cubing; widens the needles. 0 = original

# --- void pits (straight-walled shafts to the abyss, between spires) ---
VOID_PIT_CELL = 48       # one potential pit per NxN area
VOID_PIT_SIZE = 5        # width of square shaft in blocks
VOID_PIT_CHANCE = 0.3    # chance a cell has a pit
VOID_PIT_SALT = 42

# --- what happens inside a CLASSIC zone ---
# Swiss cheese wall from bedrock to height limit, tunnels along Z axis.
CLASSIC_FILL = 1.5          # bias toward solid (higher = more wall, less hole)
CLASSIC_MAIN_SCALE = 0.03   # horizontal scale of main carving (~33 block features)
CLASSIC_MAIN_YSCALE = 0.03  # vertical scale (same = roughly square cross-section)
CLASSIC_MAIN_AMP = 8.0      # amplitude of main carving noise
CLASSIC_DETAIL_SCALE = 0.08 # finer detail layer
CLASSIC_DETAIL_YSCALE = 0.1
CLASSIC_DETAIL_AMP = 3.0

# --- what happens inside a CAVERNS zone ---
# Т, 2026-08-31: "вся зона состоит из сплошных слоев больших пещер". Floors every CAVERNS_PERIOD
# blocks, thin, with a huge open cave between each pair, all the way up. The top tier has no roof
# at all - the zone is an open wound seen from the sky.
CAVERNS_PERIOD = 48        # blocks between one floor and the next
CAVERNS_THICKNESS = 14     # how thick a floor slab is. Keep well above the 8-block noise grid.
CAVERNS_BASE_Y = -60       # a floor is centred here, so the world floor is solid to stand on
CAVERNS_TOP_Y = 140        # last complete tier; above this the roof is eaten away
CAVERNS_OPEN_SPAN = 35     # blocks over which the roof fades out, so the rim is ragged not sawn
CAVERNS_WOBBLE_AMP = 2.0   # long-wave warp of the floors. 1.0 ~ moves a surface by thickness/2.
CAVERNS_DETAIL_AMP = 0.7   # short-wave roughness on the same surfaces

# --- caverns shafts: straight down, through every tier, through the bedrock, into nothing ---
# ⚠ MUST MATCH the CAVERNS_PIT_* constants in VoidShaftClear.java, which is what removes the
# bedrock lid and the aquifer water the density function cannot see.
CAVERNS_PIT_CELL = 544     # one shaft per 544x544, same rhythm as the towers (spacing 34 chunks)
CAVERNS_PIT_SIZE = 34      # nominal width; shape_variety rolls 0.5x..1.5x and picks a shape
CAVERNS_PIT_CHANCE = 0.9
CAVERNS_PIT_SALT = 7717


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


def ridge(name, offset=0.0):
    """
    1 - |noise|. Peaks at 1.0 exactly where the noise crosses zero, which is a thin winding line,
    and falls off sharply either side. This is what makes edges sharp instead of rounded - plain
    noise has no sharp features anywhere, no matter how hard you scale it.
    Offset > 0 widens the peak, making the resulting needle thicker.
    """
    r = add(const(1.0), mul(absolute(noise(name)), const(-1.0)))
    return add(r, const(offset)) if offset else r


def needle(name_a, name_b, thickness=0.0):
    """
    Two ridge line-sets multiplied together. Each one alone gives walls; where two independent
    sets cross you get isolated points, and cubing each one first makes those points narrow.
    Result is 0..1, near zero almost everywhere, spiking to 1 at the crossings.
    """
    return mul(cube(ridge(name_a, thickness)), cube(ridge(name_b, thickness)))


def zone_grid():
    return {
        "type": "farlands:zone_grid",
        "cell_size": ZONE_CELL,
        "rarity": ZONE_RARITY,
        "salt": ZONE_SALT,
        "zone_types": ZONE_TYPES,
    }


def in_zone(when_in, when_out):
    """Applies `when_in` only inside a corruption rectangle. Hard edges, no blending."""
    return {
        "type": "minecraft:range_choice",
        "input": zone_grid(),
        "min_inclusive": 0.5,
        "max_exclusive": 10.0,
        "when_in_range": when_in,
        "when_out_of_range": when_out,
    }


def in_zone_typed(type_fns, vanilla_fn):
    """
    Route to a different density per zone type. The mask returns type+1, so spires is 1.0,
    classic 2.0, caverns 3.0, and 0.0 means no zone here. Built as nested range_choice from the
    last type backwards, so adding a fourth type is one more entry in the list.
    """
    grid = zone_grid()
    node = vanilla_fn
    for index in range(len(type_fns) - 1, -1, -1):
        node = {
            "type": "minecraft:range_choice",
            "input": grid,
            "min_inclusive": index + 0.5,
            "max_exclusive": index + 1.5,
            "when_in_range": type_fns[index],
            "when_out_of_range": node,
        }
    return node


def void_pit(cell=None, size=None, chance=None, salt=None, variety=False):
    """Vertical shafts with straight walls. Returns -100 inside, 0 outside."""
    return {
        "type": "farlands:void_pit",
        "cell_size": VOID_PIT_CELL if cell is None else cell,
        "pit_size": VOID_PIT_SIZE if size is None else size,
        "chance": VOID_PIT_CHANCE if chance is None else chance,
        "salt": VOID_PIT_SALT if salt is None else salt,
        "shape_variety": variety,
    }


def cavern_layers():
    """Floor slabs stacked up the whole world. Computed in Java: see CavernLayersDensityFunction."""
    return {
        "type": "farlands:cavern_layers",
        "period": CAVERNS_PERIOD,
        "thickness": CAVERNS_THICKNESS,
        "base_y": CAVERNS_BASE_Y,
    }


def tunnel_noise(seed, scale, y_scale, octaves=4):
    """2D noise ignoring Z -> tunnels along Z. Computed in Java."""
    return {
        "type": "farlands:tunnel_noise",
        "scale": scale,
        "y_scale": y_scale,
        "octaves": octaves,
        "seed": seed,
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

    spires = mul(needle("farlands:spire_a", "farlands:spire_b", SPIRE_THICKNESS), const(SPIRE_HEIGHT))
    pits = mul(needle("farlands:pit_a", "farlands:pit_b"), const(-PIT_DEPTH))
    terrain = add(base, add(spires, add(pits, void_pit())))

    # Floating spires: the same crossing-ridge trick, but with no height gradient, so the
    # column is solid all the way through the window and cut off flat at both ends.
    columns = add(mul(needle("farlands:float_a", "farlands:float_b"), const(4.0)), const(-FLOAT_CUTOFF))
    window = add(
        gradient(FLOAT_BOTTOM - 4, -6.0, FLOAT_BOTTOM, 0.0),
        gradient(FLOAT_TOP, 0.0, FLOAT_TOP + 4, -6.0),
    )
    floating = add(columns, window)

    return dmax(terrain, floating)


def classic_density():
    """
    Classic Far Lands: solid mass from bedrock (-64) to height limit (320), carved into
    Swiss cheese by 2D noise that ignores Z (tunnels run along Z axis). Surface rules
    still apply at every solid/air boundary, so grass, trees, etc. generate normally.
    """
    fill = const(CLASSIC_FILL)
    main = mul(
        tunnel_noise(0, CLASSIC_MAIN_SCALE, CLASSIC_MAIN_YSCALE, octaves=4),
        const(-CLASSIC_MAIN_AMP),
    )
    detail = mul(
        tunnel_noise(1, CLASSIC_DETAIL_SCALE, CLASSIC_DETAIL_YSCALE, octaves=3),
        const(-CLASSIC_DETAIL_AMP),
    )
    return add(fill, add(main, detail))


def caverns_density():
    """
    Tier after tier of enormous open cave, floor slabs between them, the top tier with no roof.

    The slab stack itself is exact (Java, a function of Y), so the tier height is the number you
    typed. Everything that makes it not look like a parking garage is added on top:
      wobble  - long waves that lift and drop whole floors by ten blocks or so
      detail  - short roughness on the same surfaces
      roof    - a gradient that goes hard negative above CAVERNS_TOP_Y, so the last tier is open
                to the sky instead of capped. This is the "пещеры, вышедшие на поверхность" part.
      shafts  - void pits, but wider, rarer and of varying shape, straight through every tier.
                VoidShaftClear then removes the bedrock and the aquifer water underneath them.
    """
    layers = cavern_layers()
    wobble = mul(noise("farlands:cavern_wobble", 1.0, 1.0), const(CAVERNS_WOBBLE_AMP))
    detail = mul(noise("farlands:cavern_detail", 1.0, 1.0), const(CAVERNS_DETAIL_AMP))
    roof = gradient(CAVERNS_TOP_Y, 0.0, CAVERNS_TOP_Y + CAVERNS_OPEN_SPAN, -14.0)
    shafts = void_pit(
        cell=CAVERNS_PIT_CELL,
        size=CAVERNS_PIT_SIZE,
        chance=CAVERNS_PIT_CHANCE,
        salt=CAVERNS_PIT_SALT,
        variety=True,
    )
    return add(add(layers, add(wobble, detail)), add(roof, shafts))


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
    router["final_density"] = in_zone_typed(
        [corrupted_density(), classic_density(), caverns_density()],
        router["final_density"],
    )
    # Ridges left vanilla: biome source uses the overworld preset, so biomes are fully
    # vanilla everywhere. Zones in a desert get sand, in ice spikes get ice, etc.
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
