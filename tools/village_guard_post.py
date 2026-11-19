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


# ── 每个群系的建筑几何（不同群系可以是完全不同的投影与朝向）──────────
# src 是投影文件名（相对 --src-dir）；entrance 是门口最外一格的台阶（放 jigsaw），
# marker 是门内一格（守卫站这里）；orientation 必须指向**门外**方向。
BIOME_SPECS = {
    # 平原（2026-11-19 重制版）：9×19×19，主入口在北端，门外一格就是橡木台阶。
    "plains": {
        "src": "plains.litematic",
        "entrance": (4, 0, 0),
        "marker": (4, 1, 2),
        "orientation": "north_up",       # 门朝 -z（北）
        "final_state": "minecraft:oak_stairs"
                       "[facing=south,half=bottom,shape=straight,waterlogged=false]",
    },
    "snowy": {
        "src": "snow.litematic",
        "entrance": (0, 0, 4),
        "marker": (2, 1, 4),
        "orientation": "west_up",        # 门朝 -x（西）
        "final_state": "minecraft:spruce_stairs"
                       "[facing=east,half=bottom,shape=straight,waterlogged=false]",
    },
    # 针叶林：19×19×7，主入口在东侧的**石砌门廊** ((17,1,3) facing=west)。
    # 门廊地面 (18,0,3) 正好是最外一格的圆石台阶，jigsaw 放这里，街道接在 y=0。
    "taiga": {
        "src": "04.litematic",
        "entrance": (18, 0, 3),
        "marker": (16, 1, 3),
        "orientation": "east_up",        # 门朝 +x（东）
        "final_state": "minecraft:cobblestone",
    },
    # 热带草原：21×19×11，主入口在东侧 ((19,1,5) facing=west)，门外没有台阶，
    # 门正下方 (19,0,5) 是承重原木；jigsaw 放这格、final_state 还原原木，街道接在 y=0。
    "savanna": {
        "src": "idk_how_to_spell.litematic",
        "entrance": (19, 0, 5),
        "marker": (18, 1, 5),
        "orientation": "east_up",        # 门朝 +x（东）
        "final_state": "minecraft:acacia_log[axis=y]",
    },
    # 沙漠：17×19×7，门直接开在西侧边界上（(0,1,3) facing=east），没有外置台阶。
    # 为了让街道接在**地面高度**（村庄街道 y=0），jigsaw 放在门正下方那格，final_state 还原地板。
    "desert": {
        "src": "desert.litematic",
        "entrance": (0, 0, 3),
        "marker": (1, 1, 3),
        "orientation": "west_up",        # 门朝 -x（西）
        "final_state": "minecraft:smooth_sandstone",
    },
}


def load_region(path):
    root = load_root(path)
    region = list(root["Regions"].values())[0]
    size = region["Size"]
    sx, sy, sz = size["x"], size["y"], size["z"]
    palette = region["BlockStatePalette"]
    states = unpack_states(region["BlockStates"], len(palette), sx * sy * sz)
    return root, region, sx, sy, sz, palette, states


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


def build_biome(biome, spec, src, out_dir, name):
    root, region, sx, sy, sz, palette, states = load_region(src)
    print("  [%s] src=%s  size=(%d,%d,%d)  palette=%d  cells=%d"
          % (biome, os.path.basename(src), sx, sy, sz, len(palette), len(states)))
    print("      source MinecraftDataVersion = %s" % root.get("MinecraftDataVersion"))

    def idx(n):
        x = n % sx
        z = (n // sx) % sz
        y = n // (sx * sz)
        return x, y, z

    def cell(x, y, z):
        return x + z * sx + y * sx * sz

    entrance, marker = tuple(spec["entrance"]), tuple(spec["marker"])
    for (px, py, pz), what in ((entrance, "jigsaw"), (marker, "marker")):
        if not (0 <= px < sx and 0 <= py < sy and 0 <= pz < sz):
            raise SystemExit("[%s] %s 坐标 %s 超出结构范围 (%d,%d,%d)"
                             % (biome, what, (px, py, pz), sx, sy, sz))
        cur = palette[states[cell(px, py, pz)]]["Name"]
        print("      %-6s @ %s  currently = %s" % (what, (px, py, pz), cur))

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
        ("Properties", (10, OrderedDict([("orientation", (8, spec["orientation"]))]))),
    ]))
    marker_idx = len(out_palette)
    out_palette.append(OrderedDict([("Name", (8, MARKER_BLOCK))]))

    te_by_pos = {}
    for te in region.get("TileEntities", []):
        te_by_pos[(te.get("x"), te.get("y"), te.get("z"))] = te

    ent_cell = cell(*entrance)
    mark_cell = cell(*marker)
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
            entry["state"] = (3, jigsaw_idx)
            entry["nbt"] = (10, OrderedDict([
                ("final_state", (8, spec["final_state"])),
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
    dst = os.path.join(out_dir, "%s_%s.nbt" % (name, biome))
    with gzip.open(dst, "wb") as f:
        f.write(nbt_bytes)
    print("      wrote %s (%d bytes, %d blocks)" % (dst, os.path.getsize(dst), len(blocks)))
    return dst


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=None,
                    help="单个投影文件（覆盖群系默认值，仅用于单群系试跑）")
    ap.add_argument("--src-dir", default=None,
                    help="投影所在目录；按 BIOME_SPECS 里的文件名逐个取")
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
    if not args.src and not args.src_dir:
        raise SystemExit("至少要给 --src-dir（多群系）或 --src（单文件）")

    os.makedirs(args.out, exist_ok=True)
    for biome in biomes:
        spec = BIOME_SPECS.get(biome, {
            "src": None, "entrance": ENTRANCE, "marker": MARKER,
            "orientation": JIGSAW_ORIENTATION, "final_state": JIGSAW_FINAL_STATE,
        })
        src = args.src
        if not src:
            if not spec["src"]:
                raise SystemExit("群系 %s 还没有默认投影，请用 --src 指定" % biome)
            src = os.path.join(args.src_dir, spec["src"])
        if not os.path.exists(src):
            raise SystemExit("找不到投影文件：%s" % src)
        build_biome(biome, spec, src, args.out, args.name)

    # 清理本次没选中的群系结构，避免「模板池里没有它、包里却还留着」
    pattern = os.path.join(args.out, "%s_*.nbt" % args.name)
    keep = {"%s_%s.nbt" % (args.name, b) for b in biomes}
    for stale in glob.glob(pattern):
        if os.path.basename(stale) not in keep:
            os.remove(stale)
            print("  pruned %s（该群系本次未生成）" % stale)

    print("biomes: %s" % " ".join(biomes))


if __name__ == "__main__":
    sys.exit(main())
