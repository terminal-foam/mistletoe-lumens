import gzip, struct, os
from collections import Counter

# ── 读 litematic ────────────────────────────────────────────────
class R:
    def __init__(self, data): self.d = data; self.i = 0
    def u(self, n): v = int.from_bytes(self.d[self.i:self.i+n], 'big'); self.i += n; return v
    def s(self, n): v = self.d[self.i:self.i+n].decode('utf-8', 'replace'); self.i += n; return v

def parse_payload(r, t, name):
    if t == 8: ln = r.u(2); return (t, name, r.s(ln))
    if t == 9:
        et = r.u(1); ln = r.u(4); val = []
        for _ in range(ln): val.append(parse_payload(r, et, None)[2])
        return (t, name, val)
    if t == 10:
        val = {}
        while True:
            ct = r.u(1)
            if ct == 0: break
            cn = r.s(r.u(2)); val[cn] = parse_payload(r, ct, cn)[2]
        return (t, name, val)
    if t == 1: v = struct.unpack('>b', r.d[r.i:r.i+1])[0]; r.i += 1
    elif t == 2: v = struct.unpack('>h', r.d[r.i:r.i+2])[0]; r.i += 2
    elif t == 3: v = struct.unpack('>i', r.d[r.i:r.i+4])[0]; r.i += 4
    elif t == 4: v = struct.unpack('>q', r.d[r.i:r.i+8])[0]; r.i += 8
    elif t == 5: v = struct.unpack('>f', r.d[r.i:r.i+4])[0]; r.i += 4
    elif t == 6: v = struct.unpack('>d', r.d[r.i:r.i+8])[0]; r.i += 8
    elif t == 7: ln = r.u(4); v = list(r.d[r.i:r.i+ln]); r.i += ln
    elif t == 11: ln = r.u(4); v = list(struct.unpack('>%di' % ln, r.d[r.i:r.i+4*ln])); r.i += 4*ln
    elif t == 12: ln = r.u(4); v = list(struct.unpack('>%dq' % ln, r.d[r.i:r.i+8*ln])); r.i += 8*ln
    else: raise ValueError(t)
    return (t, name, v)

# ── 写 NBT ──────────────────────────────────────────────────────
def w_str(s):
    b = s.encode('utf-8'); return struct.pack('>H', len(b)) + b

def nbt_compound(d):
    out = b''
    for k, (t, val) in d.items():
        out += struct.pack('>b', t) + w_str(k) + nbt_payload(t, val)
    return out + b'\x00'

def nbt_payload(t, val):
    if t == 1: return struct.pack('>b', val)
    if t == 2: return struct.pack('>h', val)
    if t == 3: return struct.pack('>i', val)
    if t == 4: return struct.pack('>q', val)
    if t == 5: return struct.pack('>f', val)
    if t == 6: return struct.pack('>d', val)
    if t == 7: return struct.pack('>i', len(val)) + bytes(val)
    if t == 8: return w_str(val)
    if t == 9:
        et, items = val
        out = struct.pack('>bi', et, len(items))
        for it in items: out += nbt_payload(et, it)
        return out
    if t == 10: return nbt_compound(val)
    if t == 11: return struct.pack('>i', len(val)) + b''.join(struct.pack('>i', x) for x in val)
    if t == 12: return struct.pack('>i', len(val)) + b''.join(struct.pack('>q', x) for x in val)
    raise ValueError(t)

# ── 主流程 ──────────────────────────────────────────────────────
src = r'D:\PCL 正式版 2.10.6\.minecraft\versions\1.21.1的一个整合包\schematics\boss_stage.litematic'
data = gzip.open(src, 'rb').read()
r = R(data)
t = r.u(1); name = r.s(r.u(2)); root = parse_payload(r, t, name)[2]

region = root['Regions']['boss_stage']
size = region['Size']
sx, sy, sz = size['x'], size['y'], size['z']
palette = region['BlockStatePalette']
words = region['BlockStates']
bits = max(1, (len(palette) - 1).bit_length())
mask = (1 << bits) - 1
total = sx * sy * sz

def get_state(idx):
    start = idx * bits
    sl = start >> 6
    sb = start & 63
    v = (words[sl] >> sb) & mask
    if sb + bits > 64:
        v |= (words[sl + 1] << (64 - sb)) & mask
    return v

states = [get_state(i) for i in range(total)]
non_air = sum(1 for s in states if s != 0)
print('size', (sx, sy, sz), 'palette', len(palette), 'non-air', non_air)

def idx_to_pos(n):
    x = n % sx
    z = (n // sx) % sz
    y = n // (sx * sz)
    return x, y, z

# palette
out_palette = []
for st in palette:
    d = {'Name': (8, st['Name'])}
    props = st.get('Properties')
    if props:
        d['Properties'] = (10, {k: (8, v) for k, v in props.items()})
    out_palette.append(d)

# tile entities
te_by_pos = {}
for te in region.get('TileEntities', []):
    te_by_pos[(te.get('x'), te.get('y'), te.get('z'))] = te

def conv(v):
    if isinstance(v, bool): return (1, 1 if v else 0)
    if isinstance(v, int): return (3, v)
    if isinstance(v, float): return (6, v)
    if isinstance(v, str): return (8, v)
    if isinstance(v, list):
        if not v: return (9, (8, []))
        if all(isinstance(i, int) for i in v): return (9, (3, v))
        if all(isinstance(i, float) for i in v): return (9, (6, v))
        if all(isinstance(i, str) for i in v): return (9, (8, v))
        if all(isinstance(i, dict) for i in v): return (9, (10, [conv_dict(i) for i in v]))
        return (9, (8, [str(i) for i in v]))
    if isinstance(v, dict): return (10, conv_dict(v))
    return (8, str(v))

def conv_dict(d):
    return {k: conv(v) for k, v in d.items()}

blocks = []
for n, s in enumerate(states):
    if s == 0:
        continue
    x, y, z = idx_to_pos(n)
    entry = {
        'pos': (9, (3, [x, y, z])),
        'state': (3, s),
    }
    te = te_by_pos.get((x, y, z))
    if te is not None:
        te_nbt = {k: v for k, v in te.items() if k not in ('x', 'y', 'z', 'keepPacked')}
        entry['nbt'] = (10, conv_dict(te_nbt))
    blocks.append(entry)
print('blocks emitted', len(blocks))

ents = []
for e in region.get('Entities', []):
    eid = e.get('id'); pos = e.get('Pos')
    if not eid or not pos: continue
    bx, by, bz = int(pos[0] // 1), int(pos[1] // 1), int(pos[2] // 1)
    nbt = {'id': (8, eid), 'PersistenceRequired': (1, 1)}
    extra = e.get('NBT')
    if isinstance(extra, dict):
        for k, v in extra.items():
            if k in ('id', 'Pos', 'Motion', 'UUID', 'UUIDMost', 'UUIDLeast'): continue
            nbt[k] = conv(v)
    ents.append({
        'pos': (9, (6, [float(pos[0]), float(pos[1]), float(pos[2])])),
        'blockPos': (9, (3, [bx, by, bz])),
        'nbt': (10, nbt),
    })
print('entities emitted', len(ents))

out = {
    'DataVersion': (3, 3955),
    'size': (9, (3, [sx, sy, sz])),
    'palette': (9, (10, out_palette)),
    'blocks': (9, (10, blocks)),
    'entities': (9, (10, ents)),
}
nbt_bytes = b'\x0a' + w_str('') + nbt_compound(out)
dst = r'E:\dsh\mistletoe-classic\src\main\resources\data\mistletoe\structure\boss_stage.nbt'
os.makedirs(os.path.dirname(dst), exist_ok=True)
with gzip.open(dst, 'wb') as f: f.write(nbt_bytes)
print('written', dst, os.path.getsize(dst))
