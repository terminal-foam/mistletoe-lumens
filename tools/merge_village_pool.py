"""把「流明守卫哨所」追加进原版村庄的房屋模板池（5 个生物群系各一套）。

背景（1.21.1 实测）：
  * NeoForge 21.1.248 的 StructureModifier 只有 AddSpawns / ClearSpawns / RemoveSpawns，
    **没有**「追加模板池元素」的类型，所以数据包层面无法原地追加；
  * 因此只能整体覆写 data/minecraft/worldgen/template_pool/village/<biome>/houses.json。
  * 代价：本模组「拥有」了这 5 个文件，与其他同样加村庄建筑的模组/数据包互斥（后加载者胜）。

原版基准（tools/vanilla_pools/ 里是 1.21.1 原文件的副本）：
    plains  37 条 / 总权重 87
    desert  29 条 / 总权重 72
    savanna 32 条 / 总权重 81
    snowy   31 条 / 总权重 68
    taiga   28 条 / 总权重 76

用法：
    python tools/merge_village_pool.py --weight 10                  # 默认只做平原 + 草原
    python tools/merge_village_pool.py --weight 10 --biomes plains savanna desert snowy taiga
    python tools/merge_village_pool.py --biomes plains              # 只做平原

    --biomes 决定覆写哪几个群系的房屋池；不在列表里的 village/<群系>/houses.json
    会被删除，让这些群系回到原版行为（不出现本建筑）。
"""
import argparse
import json
import os
import shutil

ALL_BIOMES = ["plains", "desert", "savanna", "snowy", "taiga"]
DEFAULT_BIOMES = ["plains", "savanna"]
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC_DIR = os.path.join(HERE, "vanilla_pools")
OUT_DIR = os.path.join(ROOT, "src", "main", "resources", "data", "minecraft",
                       "worldgen", "template_pool", "village")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weight", type=int, default=10,
                    help="本建筑在房屋池里的权重（默认 10；原版单栋房子 1~5）")
    ap.add_argument("--biomes", nargs="+", default=DEFAULT_BIOMES,
                    help="要接入的群系（可选：%s），默认 %s"
                         % (" ".join(ALL_BIOMES), " ".join(DEFAULT_BIOMES)))
    args = ap.parse_args()

    unknown = [b for b in args.biomes if b not in ALL_BIOMES]
    if unknown:
        raise SystemExit("未知群系：%s（可选：%s）" % (" ".join(unknown), " ".join(ALL_BIOMES)))
    biomes = args.biomes
    if not biomes:
        raise SystemExit("至少要给一个群系")

    os.makedirs(OUT_DIR, exist_ok=True)
    for biome in biomes:
        src = os.path.join(SRC_DIR, "village_%s_houses.json" % biome)
        with open(src, encoding="utf-8") as f:
            pool = json.load(f, object_pairs_hook=dict)

        total_before = sum(e["weight"] for e in pool["elements"])
        house_before = sum(e["weight"] for e in pool["elements"]
                           if e["element"].get("element_type") != "minecraft:empty_pool_element")

        pool["elements"].append({
            "weight": args.weight,
            "element": {
                # 现代单元素：按放置规则贴地，和 legacy_* 的旧行为无关（本建筑没有 structure_void）
                "element_type": "minecraft:single_pool_element",
                "location": "mistletoe:village/guard_post_%s" % biome,
                "processors": "minecraft:empty",
                "projection": "rigid",
            },
        })

        total_after = total_before + args.weight
        share = 100.0 * args.weight / total_after
        # 平均每个「房屋槽位」出本建筑的概率；一个村庄通常有 6~14 个房屋槽位
        dst_dir = os.path.join(OUT_DIR, biome)
        os.makedirs(dst_dir, exist_ok=True)
        dst = os.path.join(dst_dir, "houses.json")
        with open(dst, "w", encoding="utf-8", newline="\n") as f:
            json.dump(pool, f, ensure_ascii=False, indent=2)
            f.write("\n")

        print("%-8s elements %2d -> %2d   houseWeight %3d -> %3d   totalWeight %3d -> %3d   "
              "本建筑占比 %.1f%%   期望/村庄(8~14 槽) %.1f~%.1f"
              % (biome, len(pool["elements"]) - 1, len(pool["elements"]),
                 house_before, house_before + args.weight,
                 total_before, total_after, share,
                 8 * share / 100, 14 * share / 100))
        print("         -> %s" % dst)

    # 清理本次没选中的群系覆写文件，让这些群系回到原版行为
    for biome in ALL_BIOMES:
        if biome in biomes:
            continue
        stale_dir = os.path.join(OUT_DIR, biome)
        stale = os.path.join(stale_dir, "houses.json")
        if os.path.isfile(stale):
            os.remove(stale)
            print("pruned %s（该群系已退回原版村庄）" % stale)
            if not os.listdir(stale_dir):
                shutil.rmtree(stale_dir)


if __name__ == "__main__":
    main()
