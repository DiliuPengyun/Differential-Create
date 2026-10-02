"""Build the clutch housing and the flat friction-plate item sprite.

The housing reuses Minecraft/Create textures. Run this after changing geometry;
the generated JSON assets are shipped directly and require no runtime generator.
"""
from copy import deepcopy
import json
from pathlib import Path
import struct
import zlib

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


def turn_model(model):
    """Rotate +90 degrees around X, including UVs and inverted recess bounds."""
    moved = {"down": "north", "up": "south", "north": "up", "south": "down",
             "west": "west", "east": "east"}
    uv_turns = {"down": 180, "up": 0, "north": 180, "south": 0, "west": 270, "east": 90}
    for element in model["elements"]:
        lo, hi = element["from"], element["to"]
        default_uv = {
            "down": [lo[0], 16 - hi[2], hi[0], 16 - lo[2]],
            "up": [lo[0], lo[2], hi[0], hi[2]],
            "north": [16 - hi[0], 16 - hi[1], 16 - lo[0], 16 - lo[1]],
            "south": [lo[0], 16 - hi[1], hi[0], 16 - lo[1]],
            "west": [lo[2], 16 - hi[1], hi[2], 16 - lo[1]],
            "east": [16 - hi[2], 16 - hi[1], 16 - lo[2], 16 - lo[1]],
        }
        faces = {}
        for side, face in element["faces"].items():
            face.setdefault("uv", default_uv[side])
            angle = (face.get("rotation", 0) + uv_turns[side]) % 360
            if angle:
                face["rotation"] = angle
            else:
                face.pop("rotation", None)
            faces[moved[side]] = face
        element["faces"] = faces
        # Sorting all corners erases the intentionally inverted axis of the bearings.
        element["from"] = [lo[0], round(16 - hi[2], 4), lo[1]]
        element["to"] = [hi[0], round(16 - lo[2], 4), hi[1]]
    return model


def friction_plate_texture():
    """A 16px steel ring with brown segmented lining and a transparent centre."""
    rows = (
        "................", ".....SSSSSS.....", "...SSHHHHHHSS...", "..SHBBBBBBBBHS..",
        "..HBBbBSSBbBBH..", ".SHBbSS..SSbBHS.", ".SHBBH....HBBHS.", ".SHBS......SBHS.",
        ".SHBS......SBHS.", ".SHBBH....HBBHS.", ".SHBbSS..SSbBHS.", "..HBBbBSSBbBBH..",
        "..SHBBBBBBBBHS..", "...SSHHHHHHSS...", ".....SSSSSS.....", "................",
    )
    palette = {".": (0, 0, 0, 0), "S": (55, 58, 61, 255), "H": (175, 179, 174, 255),
               "B": (139, 91, 55, 255), "b": (187, 132, 75, 255)}
    raw = b"".join(b"\0" + bytes(c for pixel in row for c in palette[pixel]) for row in rows)
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    target = ASSETS / "textures/item/composite_friction_plate.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(png)

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
    # The item shows the housing with its shaft running across the icon toward the
    # viewer's right, the way Create's clutch item does. The upright shaft is turned a
    # quarter turn around X as part of the whole model, so the housing, its recessed
    # bearings and every adjuster keep their relative places and the model is exactly
    # the block model rotated, not redrawn.
    model["textures"].update(shaft="create:block/axis", shaft_end="create:block/axis_top")
    shaft = box([6, 0, 6], [10, 16, 10], "shaft", "inventory shaft")
    for side in ("up", "down"):
        shaft["faces"][side]["texture"] = "#shaft_end"
    model["elements"].append(shaft)
    turn_model(model)
    # Guard the orientation the turn exists to produce.
    sx, sy, sz = (shaft["to"][i] - shaft["from"][i] for i in range(3))
    assert sz > sy and sz > sx, f"item shaft is not horizontal: {sx} x {sy} x {sz}"
    assert not any("rotation" in e for e in model["elements"]), "item model keeps element rotations"
    write("models/item/brass_clutch.json", model)

    friction_plate_texture()
    write("models/item/composite_friction_plate.json", {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "differentialcreate:item/composite_friction_plate"}})


if __name__ == "__main__":
    main()
