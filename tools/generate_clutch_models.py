"""Build the clutch housing and friction plate as Minecraft-native cuboid models.

All surfaces reuse Minecraft/Create textures. Run this after changing geometry;
the generated JSON assets are shipped directly and require no runtime generator.
"""
from copy import deepcopy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/differentialcreate"
SIDES = ("down", "up", "north", "south", "west", "east")


def box(lo, hi, texture, name):
    return {"name": name, "from": lo, "to": hi,
            "faces": {side: {"texture": "#" + texture} for side in SIDES}}


def write(relative, data):
    target = ASSETS / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    # Keep each cuboid on one line so the generated geometry stays reviewable.
    if "elements" in data:
        elements = data.pop("elements")
        body = json.dumps(data, indent=2, ensure_ascii=False)[:-2]
        body += ',\n  "elements": [\n'
        body += ",\n".join("    " + json.dumps(e, ensure_ascii=False) for e in elements)
        body += "\n  ]\n}\n"
    else:
        body = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    target.write_text(body, encoding="utf-8")


def housing():
    casing = box([0, 0, 0], [16, 16, 16], "side", "full brass casing")
    casing["faces"]["up"] = {"uv": [0, 0, 16, 16], "rotation": 180, "texture": "#frame"}
    casing["faces"]["down"] = {"uv": [0, 0, 16, 16], "texture": "#frame"}
    # Match Create's clutch/gearshift end geometry. The reversed Y bounds are
    # intentional: the five faces form a 1-pixel recess behind the outer rim.
    # The gearbox texture supplies an opaque bearing plate around the shaft.
    elements = [casing, {
        "name": "upper recessed bearing", "from": [1.95, 16, 1.95], "to": [14.05, 15, 14.05],
        "faces": {
            "north": {"uv": [2, 15, 14, 16], "texture": "#bearing"},
            "east": {"uv": [0, 2, 1, 14], "rotation": 270, "texture": "#bearing"},
            "south": {"uv": [2, 0, 14, 1], "rotation": 180, "texture": "#bearing"},
            "west": {"uv": [15, 2, 16, 14], "rotation": 90, "texture": "#bearing"},
            "up": {"uv": [2, 2, 14, 14], "rotation": 180, "texture": "#bearing"},
        },
    }, {
        "name": "lower recessed bearing", "from": [1.95, 1, 1.95], "to": [14.05, 0, 14.05],
        "faces": {
            "north": {"uv": [2, 15, 14, 16], "rotation": 180, "texture": "#bearing"},
            "east": {"uv": [15, 2, 16, 14], "rotation": 270, "texture": "#bearing"},
            "south": {"uv": [2, 0, 14, 1], "texture": "#bearing"},
            "west": {"uv": [0, 2, 1, 14], "rotation": 90, "texture": "#bearing"},
            "down": {"uv": [2, 2, 14, 14], "texture": "#bearing"},
        },
    }]
    # A pressure-adjustment boss on each accessible side matches the value-box location.
    for turn in range(4):
        for lo, hi, texture, name in (([5, 5, 16], [11, 11, 16.2], "lining", "adjuster backplate"),
                                     ([5.5, 5.5, 16.2], [10.5, 10.5, 16.4], "brass", "adjuster bezel"),
                                     ([6.5, 6.5, 16.4], [9.5, 9.5, 16.6], "steel", "pressure adjuster"),
                                     ([7.6, 6.8, 16.6], [8.4, 9.2, 16.65], "lining", "adjuster slot"),
                                     ([11.5, 11.5, 16], [13, 13, 16.2], "indicator", "redstone indicator")):
            corners = [(lo[0], lo[2]), (lo[0], hi[2]), (hi[0], lo[2]), (hi[0], hi[2])]
            for _ in range(turn):
                corners = [(16 - z, x) for x, z in corners]
            xs, zs = zip(*corners)
            elements.append(box([min(xs), lo[1], min(zs)], [max(xs), hi[1], max(zs)], texture, name))
    return {
        "parent": "minecraft:block/block", "render_type": "minecraft:cutout_mipped",
        "textures": {"frame": "create:block/funnel/brass_funnel_frame", "bearing": "create:block/brass_gearbox",
                     "side": "create:block/brass_casing",
                     "brass": "create:block/brass_block", "steel": "create:block/axis",
                     "lining": "minecraft:block/gray_terracotta",
                     "indicator": "minecraft:block/black_terracotta", "particle": "create:block/brass_casing"},
        "elements": elements,
    }


def main():
    model = housing()
    write("models/block/brass_clutch/block.json", deepcopy(model))
    write("models/block/brass_clutch/block_powered.json", {
        "parent": "differentialcreate:block/brass_clutch/block",
        "textures": {"indicator": "minecraft:block/redstone_block"}})
    variants = {}
    for axis, rotation in (("x", {"x": 90, "y": 90}), ("y", {}), ("z", {"x": 90, "y": 180})):
        for powered in (False, True):
            suffix = "_powered" if powered else ""
            variants[f"axis={axis},powered={str(powered).lower()}"] = {
                "model": f"differentialcreate:block/brass_clutch/block{suffix}", **rotation}
    write("blockstates/brass_clutch.json", {"variants": variants})
    model["textures"].update(shaft="create:block/axis", shaft_end="create:block/axis_top")
    shaft = box([6, 0, 6], [10, 16, 10], "shaft", "inventory shaft")
    for side in ("up", "down"):
        shaft["faces"][side]["texture"] = "#shaft_end"
    model["elements"].append(shaft)
    write("models/item/brass_clutch.json", model)

    # A chamfered annular steel plate with segmented brown friction pads.
    ring = [(5, 2, 11, 4), (3, 4, 13, 6), (2, 6, 6, 10),
            (10, 6, 14, 10), (3, 10, 13, 12), (5, 12, 11, 14)]
    elements = []
    for x1, z1, x2, z2 in ring:
        elements.append(box([x1, 7, z1], [x2, 8, z2], "steel", "steel backing"))
        elements.append(box([x1 + .35, 8, z1 + .25], [x2 - .35, 8.6, z2 - .25], "friction", "friction pad"))
    write("models/item/composite_friction_plate.json", {
        "parent": "minecraft:block/block", "gui_light": "front",
        "textures": {"steel": "minecraft:block/iron_block", "friction": "minecraft:block/brown_terracotta",
                     "particle": "minecraft:block/brown_terracotta"},
        "display": {"gui": {"rotation": [35, 225, 0], "translation": [0, 0, 0], "scale": [1.05, 1.05, 1.05]},
                    "ground": {"translation": [0, 3, 0], "scale": [.5, .5, .5]},
                    "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [.65, .65, .65]},
                    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [.5, .5, .5]},
                    "fixed": {"rotation": [90, 0, 0], "scale": [.8, .8, .8]}},
        "elements": elements})


if __name__ == "__main__":
    main()
