"""Add only a parked-position hatch to Create's original gauge models.

The native texture, model elements, UVs, item display transforms and needle are
preserved. Only three small flat model quads are added for the parked zone.
Read the installed Create dependency, or pass --create-assets to extracted assets.
"""
import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/create/models/block/gauge/stressometer"
MODEL_ROOT = "assets/create/models/block/gauge/stressometer/"


def native_models(asset_directory):
    if asset_directory:
        directory = asset_directory / "models/block/gauge/stressometer"
        return {name: json.loads((directory / (name + ".json")).read_text(encoding="utf-8")) for name in ("head", "item")}
    version = next(line.split("=", 1)[1].strip() for line in (ROOT / "gradle.properties").read_text().splitlines()
                   if line.startswith("create_version="))
    homes = [Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))), Path.home() / ".gradle"]
    for home in homes:
        cache = home / "caches/modules-2/files-2.1/com.simibubi.create/create-1.21.1" / version
        for jar in sorted(cache.glob("*/*.jar")):
            if jar.name.endswith(("-sources.jar", "-javadoc.jar")):
                continue
            with ZipFile(jar) as archive:
                if MODEL_ROOT + "head.json" in archive.namelist():
                    return {name: json.loads(archive.read(MODEL_ROOT + name + ".json")) for name in ("head", "item")}
    extracted = ROOT / "build/create-reference/assets/create"
    if extracted.is_dir():
        return native_models(extracted)
    raise SystemExit("Resolve the Create dependency first, or use --create-assets <extracted assets/create>.")


def overlays():
    # Sample one opaque steel pixel from the original atlas, never a new image.
    uv = [.75, 9.75, .75, 9.75]
    marks = []
    # Native JSON models support 45-degree rotations: narrow, separated hatch
    # strokes stay diagonal rather than merging into a staircase at this scale.
    for z in (9.3, 10.15, 11):
        marks.append({"name": "DC load park", "from": [16.09, 4.44, z - .45], "to": [16.09, 4.56, z + .45],
                      "rotation": {"angle": 45, "axis": "x", "origin": [16.09, 4.5, z]},
                      "faces": {"east": {"uv": uv, "texture": "#2"}}})
    return marks


def generate(asset_directory=None):
    originals = native_models(asset_directory)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, original in originals.items():
        model = deepcopy(original)
        model["elements"].extend(overlays())
        # Keep native elements together so their preservation is easy to review.
        body = {key: value for key, value in model.items() if key != "elements"}
        serialized = json.dumps(body, ensure_ascii=False, indent=2)[:-2] + ',\n  "elements": [\n'
        serialized += ",\n".join("    " + json.dumps(element, ensure_ascii=False) for element in model["elements"])
        serialized += "\n  ]\n}\n"
        (OUTPUT / (name + ".json")).write_text(serialized, encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--create-assets", type=Path)
    generate(parser.parse_args().create_assets)
