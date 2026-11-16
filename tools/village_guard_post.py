"""把 village_structure.litematic 转成 5 份村庄建筑 NBT（每个村��生物群系一份）。

为什么是 5 份：建筑入口的 jigsaw 方块必须指向**对应群系**的街道池
(minecraft:village/<biome>/streets)，否则平原村庄会接出沙漠风格的街道。

同时在建筑内部注入：
  * minecraft:jigsaw[orientation=south_up] @ ENTRANCE  —— 让村庄街道能接到门口
  * mistletoe:guard_post                              @ MARKER   —— 守卫投放锚点（隐形方块）

用法：
    python tools/village_guard_post.py \
        --src "D:/.../village_structure.litematic" \
        --out src/main/resources/data/mistletoe/structure/village \
        --biomes plains savanna

    --biomes 决定生成哪几个群系的结构；不在列表里的 guard_post_*.nbt 会被清理掉
    （避免模板池已删、结构文件还留在包里）。
"""
import argparse
import glob
import gzip
import os
import struct
import sys
from collections import OrderedDict

# 支持的群系（每个群系一份 NBT，jigsaw 指向本群系的街道池）
ALL_BIOMES = ["plains", "desert", "savanna", "snowy", "taiga"]
# 默认只出平原 + 草原；沙漠/雪原/针叶林等变种建好后再加进来
DEFAULT_BIOMES = ["plains", "savanna"]

# 建筑几何（由投影实测得出）
ENTRANCE = (3, 0, 16)   # 门口台阶那格：jigsaw 放这里，final_state 还原成台阶
MARKER = (3, 1, 14)     # 主厅内、北门内侧一格：守卫站这里
JIGSAW_ORIENTATION = "south_up"   # 门朝 +z（南）
JIGSAW_FINAL_STATE = ("minecraft:cobblestone_stairs"
                      "[facing=north,half=bottom,shape=straight,waterlogged=false]")
MARKER_BLOCK = "mistletoe:guard_post"
DATA_VERSION = 3955   # Minecraft 1.21.1


# ── NBT 读 ────────────────────────────────────────────────────────────
class Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def u(self, n):
        v = int.from_bytes(self.d[self.i:self.i + n], "big")
        self.i += n
        return v

    def s(self, n):
        v = self.d[self.i:self.i + n].decode("utf-8", "replace")
        self.i += n
        return v


def parse_payload(r, t, name):
    if t == 1:
        v = struct.unpack(">b", r.d[r.i:r.i + 1])[0]; r.i += 1
    elif t == 2:
        v = struct.unpack(">h", r.d[r.i:r.i + 2])[0]; r.i += 2
    elif t == 3:
        v = struct.unpack(">i", r.d[r.i:r.i + 4])[0]; r.i += 4
    elif t == 4:
        v = struct.unpack(">q", r.d[r.i:r.i + 8])[0]; r.i += 8
    elif t == 5:
        v = struct.unpack(">f", r.d[r.i:r.i + 4])[0]; r.i += 4
    elif t == 6:
        v = struct.unpack(">d", r.d[r.i:r.i + 8])[0]; r.i += 8
    elif t == 7:
        ln = r.u(4); v = list(r.d[r.i:r.i + ln]); r.i += ln
    elif t == 8:
        return (t, name, r.s(r.u(2)))
    elif t == 9:
        et = r.u(1); ln = r.u(4)
        v = [parse_payload(r, et, None)[2] for _ in range(ln)]
        return (t, name, v)
    elif t == 10:
        v = OrderedDict()
        while True:
            ct = r.u(1)
            if ct == 0:
                break
            cn = r.s(r.u(2))
            v[cn] = parse_payload(r, ct, cn)[2]
        return (t, name, v)
    elif t == 11:
        ln = r.u(4); v = list(struct.unpack(">%di" % ln, r.d[r.i:r.i + 4 * ln])); r.i += 4 * ln
    elif t == 12:
        ln = r.u(4); v = list(struct.unpack(">%dq" % ln, r.d[r.i:r.i + 8 * ln])); r.i += 8 * ln
    else:
        raise ValueError("bad tag %s" % t)
    return (t, name, v)


def load_root(path):
    data = open(path, "rb").read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    r = Reader(data)
    t = r.u(1)
    name = r.s(r.u(2))
    return parse_payload(r, t, name)[2]


# ── NBT 写 ────────────────────────────────────────────────────────────
def w_str(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def nbt_payload(t, val):
    if t == 1:
        return struct.pack(">b", val)
    if t == 2:
        return struct.pack(">h", val)
    if t == 3:
        return struct.pack(">i", val)
    if t == 4:
        return struct.pack(">q", val)
    if t == 5:
        return struct.pack(">f", val)
    if t == 6:
        return struct.pack(">d", val)
    if t == 7:
        return struct.pack(">i", len(val)) + bytes(val)
    if t == 8:
        return w_str(val)
    if t == 9:
        et, items = val
        return struct.pack(">bi", et, len(items)) + b"".join(nbt_payload(et, it) for it in items)
    if t == 10:
        return nbt_compound(val)
    if t == 11:
        return struct.pack(">i", len(val)) + b"".join(struct.pack(">i", x) for x in val)
    if t == 12:
        return struct.pack(">i", len(val)) + b"".join(struct.pack(">q", x) for x in val)
    raise ValueError("bad tag %s" % t)


def nbt_compound(d):
    out = b""
    for k, (t, val) in d.items():
        out += struct.pack(">b", t) + w_str(k) + nbt_payload(t, val)
    return out + b"\x00"


# ── 位数组解包（Litematica 连续位流，条目可跨 long）──────────────────
def unpack_states(words, palette_size, volume):
    bits = max(2, (palette_size - 1).bit_length())
    mask = (1 << bits) - 1
    uv = [w & 0xFFFFFFFFFFFFFFFF for w in words]
    out = []
    for i in range(volume):
        start = i * bits
        li = start >> 6
        sb = start & 63
        if li >= len(uv):
            break
        v = uv[li] >> sb
        if sb + bits > 64 and li + 1 < len(uv):
            v |= uv[li + 1] << (64 - sb)
        out.append(v & mask)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--name", default="guard_post")
    ap.add_argument("--biomes", nargs="+", default=DEFAULT_BIOMES,
                    help="要生成结构的群系（可选：%s），默认 %s"
                         % (" ".join(ALL_BIOMES), " ".join(DEFAULT_BIOMES)))
    args = ap.parse_args()

    biomes = [b for b in args.biomes if b in ALL_BIOMES]
    unknown = [b for b in args.biomes if b not in ALL_BIOMES]
    if unknown:
        raise SystemExit("未知群系：%s（可选：%s）" % (" ".join(unknown), " ".join(ALL_BIOMES)))
    if not biomes:
        raise SystemExit("至少要给一个群系")

    root = load_root(args.src)
    region = list(root["Regions"].values())[0]
    size = region["Size"]
    sx, sy, sz = size["x"], size["y"], size["z"]
    palette = region["BlockStatePalette"]
    states = unpack_states(region["BlockStates"], len(palette), sx * sy * sz)
    print("size=(%d,%d,%d) palette=%d cells=%d" % (sx, sy, sz, len(palette), len(states)))
    print("source MinecraftDataVersion =", root.get("MinecraftDataVersion"))

    def idx(n):
        x = n % sx
        z = (n // sx) % sz
        y = n // (sx * sz)
        return x, y, z

    def cell(x, y, z):
        return x + z * sx + y * sx * sz

    # 校验注入点
    for (px, py, pz), what in ((ENTRANCE, "jigsaw"), (MARKER, "marker")):
        cur = palette[states[cell(px, py, pz)]]["Name"]
        print("  %-6s @ %s currently = %s" % (what, (px, py, pz), cur))

    # palette 复制 + 追加
    out_palette = []
    for st in palette:
        d = OrderedDict()
        d["Name"] = (8, st["Name"])
        props = st.get("Properties")
        if props:
            d["Properties"] = (10, OrderedDict((k, (8, v)) for k, v in sorted(props.items())))
        out_palette.append(d)

    jigsaw_idx = len(out_palette)
    out_palette.append(OrderedDict([
        ("Name", (8, "minecraft:jigsaw")),
        ("Properties", (10, OrderedDict([("orientation", (8, JIGSAW_ORIENTATION))]))),
    ]))
    marker_idx = len(out_palette)
    out_palette.append(OrderedDict([("Name", (8, MARKER_BLOCK))]))

    # 方块实体
    te_by_pos = {}
    for te in region.get("TileEntities", []):
        te_by_pos[(te.get("x"), te.get("y"), te.get("z"))] = te

    def conv(v):
        if isinstance(v, bool):
            return (1, 1 if v else 0)
        if isinstance(v, int):
            return (3, v)
        if isinstance(v, float):
            return (6, v)
        if isinstance(v, str):
            return (8, v)
        if isinstance(v, list):
            if not v:
                return (9, (8, []))
            if all(isinstance(i, int) and not isinstance(i, bool) for i in v):
                return (9, (3, v))
            if all(isinstance(i, float) for i in v):
                return (9, (6, v))
            if all(isinstance(i, str) for i in v):
                return (9, (8, v))
            if all(isinstance(i, dict) for i in v):
                return (9, (10, [conv_dict(i) for i in v]))
            return (9, (8, [str(i) for i in v]))
        if isinstance(v, dict):
            return (10, conv_dict(v))
        return (8, str(v))

    def conv_dict(d):
        return OrderedDict((k, conv(v)) for k, v in d.items() if not k.startswith("_"))

    os.makedirs(args.out, exist_ok=True)
    written = []
    ent_cell = cell(*ENTRANCE)
    mark_cell = cell(*MARKER)

    for biome in biomes:
        blocks = []
        for n, s in enumerate(states):
            x, y, z = idx(n)
            state = s
            entry = OrderedDict()
            entry["pos"] = (9, (3, [x, y, z]))
            if n == mark_cell:
                state = marker_idx
            entry["state"] = (3, state)
            if n == ent_cell:
                # 方块状态必须真的换成 jigsaw，否则方块实体数据会被忽略、连接器不生效
                entry["state"] = (3, jigsaw_idx)
                # jigsaw 的连接池指回本群系的街道，村庄才能继续延伸
                entry["nbt"] = (10, OrderedDict([
                    ("final_state", (8, JIGSAW_FINAL_STATE)),
                    ("name", (8, "minecraft:building_entrance")),
                    ("pool", (8, "minecraft:village/%s/streets" % biome)),
                    ("joint", (8, "aligned")),
                    ("id", (8, "minecraft:jigsaw")),
                    ("target", (8, "minecraft:building_entrance")),
                ]))
            else:
                te = te_by_pos.get((x, y, z))
                if te is not None:
                    entry["nbt"] = (10, conv_dict(
                        {k: v for k, v in te.items() if k not in ("x", "y", "z", "keepPacked")}))
            blocks.append(entry)

        out = OrderedDict()
        out["DataVersion"] = (3, DATA_VERSION)
        out["size"] = (9, (3, [sx, sy, sz]))
        out["palette"] = (9, (10, out_palette))
        out["blocks"] = (9, (10, blocks))
        out["entities"] = (9, (10, []))

        nbt_bytes = b"\x0a" + w_str("") + nbt_compound(out)
        dst = os.path.join(args.out, "%s_%s.nbt" % (args.name, biome))
        with gzip.open(dst, "wb") as f:
            f.write(nbt_bytes)
        written.append((dst, os.path.getsize(dst)))
        print("  wrote %s (%d bytes, %d blocks)" % (dst, os.path.getsize(dst), len(blocks)))

    # 清理本次没选中的群系结构，避免「模板池里没有它、包里却还留着」
    pattern = os.path.join(args.out, "%s_*.nbt" % args.name)
    for stale in sorted(glob.glob(pattern)):
        suffix = os.path.splitext(os.path.basename(stale))[0][len(args.name) + 1:]
        if suffix not in biomes:
            os.remove(stale)
            print("  pruned %s（不在本次群系列表里）" % os.path.basename(stale))

    non_air = sum(1 for s in states if s != 0)
    print("\nnon-air blocks in source: %d / %d cells" % (non_air, len(states)))
    print("entrance jigsaw @ %s, marker %s @ %s" % (ENTRANCE, MARKER_BLOCK, MARKER))
    print("biomes: %s" % " ".join(biomes))


if __name__ == "__main__":
    sys.exit(main())
