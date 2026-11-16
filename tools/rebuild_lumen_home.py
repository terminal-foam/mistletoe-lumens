import gzip
from litemapy import Schematic
from nbtlib import File, Compound, List, Int, String, Byte

src = r'D:\PCL 正式版 2.10.6\.minecraft\versions\1.21.1的一个整合包\schematics\boss_stage.litematic'
sch = Schematic.load(src)
reg = list(sch.regions.values())[0]
sx, sy, sz = reg.width, reg.height, reg.length

def block(x, y, z):
    try:
        return reg[x, y, z]
    except Exception:
        return None

# 找一个能立牌子的位置：床旁边、空气、下方实心、上方空气
candidates = []
for y in range(sy):
    for z in range(sz):
        for x in range(sx):
            b = block(x, y, z)
            if b is None or 'bed' not in str(b):
                continue
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if not (0 <= nx < sx and 0 <= nz < sz and y + 1 < sy):
                    continue
                air = block(nx, y, nz)
                below = block(nx, y - 1, nz)
                above = block(nx, y + 1, nz)
                if air is not None and str(air) == 'minecraft:air' and below is not None and str(below) != 'minecraft:air' and above is not None and str(above) == 'minecraft:air':
                    candidates.append((nx, y, nz))
print('sign candidates:', len(candidates))
for c in candidates[:10]:
    print(' ', c)

sign_pos = candidates[0] if candidates else (10, 1, 52)
print('chosen sign pos:', sign_pos)

# 生成 structure nbt
nbt = reg.to_structure_nbt()
nbt['DataVersion'] = Int(3955)

palette = nbt['palette']
blocks = nbt['blocks']

# 追加 sign 的调色板项
sign_state = Compound({
    'Name': String('minecraft:oak_sign'),
    'Properties': Compound({
        'rotation': String('0'),
        'waterlogged': String('false'),
    }),
})
palette.append(sign_state)
state_idx = len(palette) - 1

text_front = Compound({
    'messages': List[String]([
        String('{"text":"Welcome home,"}'),
        String('{"text":"%player%"}'),
        String('{"text":""}'),
        String('{"text":""}'),
    ]),
    'color': String('black'),
    'has_glowing_text': Byte(0),
})
text_back = Compound({
    'messages': List[String]([String('{"text":""}')] * 4),
    'color': String('black'),
    'has_glowing_text': Byte(0),
})
te_nbt = Compound({
    'id': String('minecraft:sign'),
    'front_text': text_front,
    'back_text': text_back,
    'is_waxed': Byte(1),
})
blocks.append(Compound({
    'pos': List[Int]([Int(sign_pos[0]), Int(sign_pos[1]), Int(sign_pos[2])]),
    'state': Int(state_idx),
    'nbt': te_nbt,
}))

out = r'E:\dsh\mistletoe-classic\src\main\resources\data\mistletoe\structure\lumen_home.nbt'
nbt.save(out, gzipped=True)
print('written', out)
print('palette', len(palette), 'blocks', len(blocks), 'entities', len(nbt['entities']))
