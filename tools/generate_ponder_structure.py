"""Rebuild the deterministic, shared Ponder stage using only Python's standard library."""
from pathlib import Path
import gzip
import struct


def string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def tag(kind, name, payload):
    return bytes([kind]) + string(name) + payload


def integers(values):
    return bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", n) for n in values)


def compounds(values):
    return bytes([10]) + struct.pack(">i", len(values)) + b"".join(values)


def compound(*values):
    return b"".join(values) + bytes([0])


def block(pos, state):
    return compound(tag(9, "pos", integers(pos)), tag(3, "state", struct.pack(">i", state)))


def main():
    # The upper marker deliberately expands PonderLevel's bounds before any
    # storyboard runs. Structure size alone does not bound its replace instructions.
    blocks = [block((x, 0, z), (x + z) % 2) for x in range(7) for z in range(7)]
    blocks.append(block((6, 5, 6), 2))
    palette = [compound(tag(8, "Name", string(name))) for name in
               ("minecraft:white_concrete", "minecraft:snow_block", "minecraft:barrier")]
    document = tag(10, "", compound(
        tag(3, "DataVersion", struct.pack(">i", 3955)),
        tag(8, "author", string("Differential Create")),
        tag(9, "size", integers((7, 6, 7))),
        tag(9, "palette", compounds(palette)),
        tag(9, "blocks", compounds(blocks)),
        tag(9, "entities", compounds([])),
    ))
    target = Path(__file__).resolve().parents[1] / "src/main/resources/assets/differentialcreate/ponder/physics.nbt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(gzip.compress(document, mtime=0))
    print(f"Wrote {target.relative_to(Path(__file__).resolve().parents[1])}: {len(blocks)} blocks")


if __name__ == "__main__":
    main()
