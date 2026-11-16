import gzip, struct

class R:
    def __init__(self, data):
        self.d = data; self.i = 0
    def u(self, n):
        v = int.from_bytes(self.d[self.i:self.i+n], 'big'); self.i += n; return v
    def s(self, n):
        v = self.d[self.i:self.i+n].decode('utf-8', 'replace'); self.i += n; return v

def parse_payload(r, t, name):
    if t == 8:
        ln = r.u(2); return (t, name, r.s(ln))
    if t == 9:
        et = r.u(1); ln = r.u(4); val = []
        for _ in range(ln):
            val.append(parse_payload(r, et, None)[2])
        return (t, name, val)
    if t == 10:
        val = {}
        while True:
            ct = r.u(1)
            if ct == 0:
                break
            cn = r.s(r.u(2))
            cv = parse_payload(r, ct, cn)[2]
            val[cn] = cv
        return (t, name, val)
    if t == 1: v = struct.unpack('>b', r.d[r.i:r.i+1])[0]; r.i += 1
    elif t == 2: v = struct.unpack('>h', r.d[r.i:r.i+2])[0]; r.i += 2
    elif t == 3: v = struct.unpack('>i', r.d[r.i:r.i+4])[0]; r.i += 4
    elif t == 4: v = struct.unpack('>q', r.d[r.i:r.i+8])[0]; r.i += 8
    elif t == 5: v = struct.unpack('>f', r.d[r.i:r.i+4])[0]; r.i += 4
    elif t == 6: v = struct.unpack('>d', r.d[r.i:r.i+8])[0]; r.i += 8
    elif t == 7:
        ln = r.u(4); v = list(r.d[r.i:r.i+ln]); r.i += ln
    elif t == 11:
        ln = r.u(4); v = list(struct.unpack('>%di' % ln, r.d[r.i:r.i+4*ln])); r.i += 4*ln
    elif t == 12:
        ln = r.u(4); v = list(struct.unpack('>%dq' % ln, r.d[r.i:r.i+8*ln])); r.i += 8*ln
    else:
        raise ValueError('tag %d' % t)
    return (t, name, v)

path = r'D:\PCL 正式版 2.10.6\.minecraft\versions\1.21.1的一个整合包\schematics\boss_stage.litematic'
data = gzip.open(path, 'rb').read()
r = R(data)
t = r.u(1)
root_name = r.s(r.u(2))
root = parse_payload(r, t, root_name)[2]
print('root:', root_name, 'Version', root.get('Version'), 'MCDataVersion', root.get('MinecraftDataVersion'))
meta = root.get('Metadata', {})
print('metadata:', {k: meta.get(k) for k in ('Name', 'Author', 'Description')})
for rn, reg in root.get('Regions', {}).items():
    pos = reg.get('Position'); size = reg.get('Size')
    pal = reg.get('BlockStatePalette', [])
    print(f'region {rn}: pos={pos} size={size} palette={len(pal)} blockstates={len(reg.get("BlockStates", []))}')
    from collections import Counter
    c = Counter(st.get('Name', '?') for st in pal)
    for k, v in c.most_common(25):
        print('  ', k, v)
    # 统计非空气方块数量（按 palette 索引在 BlockStates 中出现次数）
    bs = reg.get('BlockStates', [])
    if bs and pal:
        bits = max(1, (len(pal) - 1).bit_length())
        mask = (1 << bits) - 1
        counts = Counter()
        for word in bs:
            for j in range(0, 64, bits):
                idx = (word >> j) & mask
                if idx < len(pal):
                    counts[idx] += 1
        print('  top blocks by volume:')
        for idx, n in counts.most_common(10):
            print('   ', pal[idx].get('Name'), n)
