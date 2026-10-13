"""Create-native textures and compact additions to the existing bogey meshes."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / 'src/main/resources/assets/differentialcreate/models/block'

def save(path, data):
    for element in data.get('elements', []):
        assert all(-16 <= v <= 32 for key in ('from', 'to') for v in element[key]), path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def box(low, high, texture, uv=(0, 0, 16, 16), rotation=None):
    element = {'from': low, 'to': high,
        'faces': {side: {'uv': list(uv), 'texture': texture}
                  for side in ('north', 'south', 'east', 'west', 'up', 'down')}}
    if rotation:
        element['rotation'] = rotation
    return element

def bogey():
    housing, pistons = [], []
    # All engine parts sit BETWEEN the wheel axles and inside the native frame.
    # The native frame/wheels remain untouched; small caps and intake sockets
    # are the only details visible through its openings.
    for x in (-3.5, 3.5):
        housing.append(box([x-2.5, 10, -7], [x+2.5, 16, 2], '#frame', (2, 2, 7, 8)))
        for z in (-7.25, 1.5):
            housing.append(box([x-2, 10.5, z], [x+2, 15.5, z+.75], '#engine', (10, 2, 14, 6)))
        pistons.append(box([x-.5, 12.5, 2], [x+.5, 13.5, 7], '#axis', (6, 0, 10, 10)))
    housing.append(box([-6, 15, -3], [6, 17, 1], '#frame', (1, 1, 13, 3)))
    for side in (-1, 1):
        low, high = sorted((side*6, side*9))
        socket = box([low, 11, -2], [high, 15, 2], '#pipes', (1, 11, 6, 16))
        housing.append(socket)
    textures = {'frame': 'create:block/bogey/frame2', 'engine': 'create:block/engine',
        'pipes': 'create:block/pipes', 'axis': 'create:block/axis', 'particle': 'create:block/bogey/frame2'}
    for name, elements in [('cylinders', housing), ('pistons', pistons)]:
        # Keep the model's coordinate origin consistent with the native bogey.
        for element in elements:
            for key in ('from', 'to'):
                element[key][0] += 8
                element[key][2] += 8
        save(MODELS / f'steam_bogey/{name}.json',
             {'parent': 'minecraft:block/block', 'textures': textures, 'elements': elements})

def levers():
    for gear, angle in enumerate((-45, -22.5, 0, 22.5)):
        base = box([4, 0, 3], [12, 3, 13], '#side')
        base['faces']['up']['texture'] = '#end'
        base['faces']['down']['texture'] = '#end'
        handle = box([7, 3, 7], [9, 13, 9], '#handle', (7, 6, 9, 16),
                     {'origin': [8, 3, 8], 'axis': 'x', 'angle': angle})
        for side in ('up', 'down'):
            handle['faces'][side]['uv'] = [7, 6, 9, 8]
        save(MODELS / f'gear_lever_{gear}.json', {'parent': 'minecraft:block/block',
            'textures': {'side': 'create:block/railway_casing_side', 'end': 'create:block/railway_casing',
                         'handle': 'minecraft:block/lever', 'particle': 'create:block/railway_casing_side'},
            'elements': [base, handle]})

def hose_item():
    # A compact coil with native rubber-hose and pipe-coupling textures.
    elements = []
    for low, high in [([3, 3, 6], [6, 12, 9]), ([10, 3, 6], [13, 12, 9]),
                      ([5, 2, 6], [11, 5, 9]), ([5, 10, 6], [11, 13, 9])]:
        elements.append(box(low, high, '#hose', (12, 0, 16, 16)))
    elements.append(box([2.5, 11, 5.5], [6.5, 14, 9.5], '#pipes', (1, 11, 6, 16)))
    elements.append(box([9.5, 1, 5.5], [13.5, 4, 9.5], '#pipes', (1, 11, 6, 16)))
    save(MODELS.parent / 'item/steam_hose.json', {'parent': 'minecraft:block/block',
        'textures': {'hose': 'create:block/hose', 'pipes': 'create:block/pipes', 'particle': 'create:block/hose'},
        'elements': elements,
        'display': {'gui': {'rotation': [20, -30, 0], 'translation': [0, 0, 0], 'scale': [1.05, 1.05, 1.05]},
                    'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [.65, .65, .65]},
                    'thirdperson_righthand': {'rotation': [0, -90, 25], 'translation': [0, 3, 1], 'scale': [.5, .5, .5]}}})

if __name__ == '__main__':
    bogey()
    levers()
    hose_item()
