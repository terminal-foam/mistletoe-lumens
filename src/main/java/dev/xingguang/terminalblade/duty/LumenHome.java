package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「流明星们的家」（{@code mistletoe:lumen_home}）的定位、坐标换算与住户查询。
 *
 * <p>结构在生成时会带随机旋转，所以这里不直接使用模板里的相对坐标，而是在第一次需要时
 * <b>用世界里实际存在的 34 扇铁门反推</b>「旋转 + 平移」：拿 15 组双扇门的局部坐标去匹配
 * 世界里的铁门下半格，覆盖率最高的一组即为正确解。这样既不需要依赖 jigsaw piece 的
 * 轴心约定，也能在结构被玩家改动过一部分时仍然大致可用。</p>
 */
public final class LumenHome {
    public static final ResourceKey<Structure> KEY =
            ResourceKey.create(Registries.STRUCTURE, TerminalBlade.id("lumen_home"));

    /** 大厅中央附近的三个刷怪点（结构局部坐标）。 */
    public static final int[][] HALL_POINTS = {{26, 1, 25}, {33, 1, 30}, {22, 1, 34}};

    /** 大厅边缘的集结点（结构局部坐标）。 */
    public static final int[][] RALLY_POINTS = {{13, 1, 27}, {26, 1, 13}, {37, 1, 27}, {26, 1, 41}};

    /** 15 组双扇卧室铁门的 30 个下半格（结构局部坐标）。 */
    public static final int[][] DOOR_LEAVES = {
            {10, 1, 14}, {10, 1, 15}, {10, 1, 20}, {10, 1, 21}, {10, 1, 26}, {10, 1, 27},
            {10, 1, 32}, {10, 1, 33}, {10, 1, 38}, {10, 1, 39},
            {14, 1, 10}, {15, 1, 10}, {14, 1, 44}, {15, 1, 44},
            {20, 1, 10}, {21, 1, 10}, {20, 1, 44}, {21, 1, 44},
            {26, 1, 10}, {27, 1, 10}, {26, 1, 44}, {27, 1, 44},
            {32, 1, 10}, {33, 1, 10}, {32, 1, 44}, {33, 1, 44},
            {38, 1, 10}, {39, 1, 10}, {38, 1, 44}, {39, 1, 44}
    };

    /** 另外 4 扇单扇铁门（不参与集结开门）。 */
    public static final int[][] SINGLE_LEAVES = {{10, 1, 11}, {10, 1, 43}, {11, 1, 10}, {11, 1, 44}};

    /** 15 组双扇卧室门，每组两个门叶（结构局部坐标）。 */
    public static final int[][][] DOOR_PAIRS = {
            {{10, 1, 14}, {10, 1, 15}},
            {{10, 1, 20}, {10, 1, 21}},
            {{10, 1, 26}, {10, 1, 27}},
            {{10, 1, 32}, {10, 1, 33}},
            {{10, 1, 38}, {10, 1, 39}},
            {{14, 1, 10}, {15, 1, 10}},
            {{14, 1, 44}, {15, 1, 44}},
            {{20, 1, 10}, {21, 1, 10}},
            {{20, 1, 44}, {21, 1, 44}},
            {{26, 1, 10}, {27, 1, 10}},
            {{26, 1, 44}, {27, 1, 44}},
            {{32, 1, 10}, {33, 1, 10}},
            {{32, 1, 44}, {33, 1, 44}},
            {{38, 1, 10}, {39, 1, 10}},
            {{38, 1, 44}, {39, 1, 44}}
    };

    /** 四种旋转矩阵，{a,b,c,d} 表示 (x,z) -> (a*x + b*z, c*x + d*z)。 */
    private static final int[][] ROTATIONS = {
            {1, 0, 0, 1},
            {0, -1, 1, 0},
            {-1, 0, 0, -1},
            {0, 1, -1, 0}
    };

    public final ServerLevel level;
    public final BoundingBox box;

    private int[] rot;
    private BlockPos zero;
    private boolean solved;
    private boolean solvable;
    private int matched;
    /** 结构一旦生成就不会移动，按「维度 + 结构范围」缓存，避免每 10 tick 重扫 3 万方块。 */
    private static final java.util.Map<String, LumenHome> CACHE = new java.util.HashMap<>();

    private final List<BlockPos> doorLeaves = new ArrayList<>();
    private final List<BlockPos> singleLeaves = new ArrayList<>();

    private LumenHome(ServerLevel level, BoundingBox box) {
        this.level = level;
        this.box = box;
    }

    /** 取包含该位置的流明之家；不在结构内（或结构未生成）返回 null。 */
    public static LumenHome at(ServerLevel level, BlockPos pos) {
        if (pos == null) {
            return null;
        }
        try {
            Structure structure = level.registryAccess()
                    .lookupOrThrow(Registries.STRUCTURE)
                    .getOrThrow(KEY)
                    .value();
            StructureStart start = level.structureManager().getStructureWithPieceAt(pos, structure);
            if (start == null || !start.isValid()) {
                return null;
            }
            BoundingBox box = start.getBoundingBox();
            if (box == null || !box.isInside(pos)) {
                return null;
            }
            String key = level.dimension().location() + "@" + box.minX() + "," + box.minY() + "," + box.minZ();
            LumenHome cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            LumenHome home = new LumenHome(level, box);
            if (CACHE.size() > 128) {
                CACHE.clear();
            }
            CACHE.put(key, home);
            return home;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean contains(BlockPos pos) {
        return pos != null && box.isInside(pos);
    }

    public AABB aabb() {
        return new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
    }

    public AABB aabb(int inflate) {
        return aabb().inflate(inflate);
    }

    /** 反推「旋转 + 平移」。成功返回 true；失败（铁门几乎全被拆/替换）返回 false。 */
    public boolean solve() {
        if (solved) {
            return solvable;
        }
        Set<BlockPos> world = collectWorldDoors();
        if (world.size() < 8) {
            // 区块还没加载完全时读到的全是空气，不要把这当成「结构损坏」，下次再试。
            BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
            BlockPos max = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
            if (!level.isLoaded(min) || !level.isLoaded(max)) {
                return false;
            }
            solved = true;
            return false;
        }
        solved = true;
        int best = -1;
        int[] bestRot = null;
        BlockPos bestZero = null;
        for (int[] r : ROTATIONS) {
            for (BlockPos w : world) {
                for (int[] l : DOOR_LEAVES) {
                    BlockPos rl = rotate(r, l);
                    BlockPos candZero = w.offset(-rl.getX(), -rl.getY(), -rl.getZ());
                    int score = 0;
                    for (int[] ll : DOOR_LEAVES) {
                        if (world.contains(candZero.offset(rotate(r, ll)))) {
                            score++;
                        }
                    }
                    if (score > best) {
                        best = score;
                        bestRot = r;
                        bestZero = candZero;
                    }
                    if (best == DOOR_LEAVES.length) {
                        break;
                    }
                }
                if (best == DOOR_LEAVES.length) {
                    break;
                }
            }
            if (best == DOOR_LEAVES.length) {
                break;
            }
        }
        if (best < 12) {
            TerminalBlade.LOGGER.warn("[流明·星] 家坐标反推失败：铁门匹配 {} / {}（结构 {}）",
                    best, DOOR_LEAVES.length, box);
            return false;
        }
        rot = bestRot;
        zero = bestZero;
        matched = best;
        // 注意：这里不能调 toWorld()——它会再进 solve()，而此刻 solvable 还是 false，
        // 会返回 null 并让后面的 List.copyOf 抛 NPE。直接用旋转+平移算。
        doorLeaves.clear();
        for (int[] l : DOOR_LEAVES) {
            doorLeaves.add(zero.offset(rotate(rot, l)));
        }
        singleLeaves.clear();
        for (int[] l : SINGLE_LEAVES) {
            singleLeaves.add(zero.offset(rotate(rot, l)));
        }
        solvable = true;
        return true;
    }

    public int matchedDoors() {
        solve();
        return matched;
    }

    public boolean solvable() {
        return solve();
    }

    public BlockPos toWorld(int[] local) {
        return toWorld(local[0], local[1], local[2]);
    }

    public BlockPos toWorld(int x, int y, int z) {
        if (!solve()) {
            return null;
        }
        return zero.offset(rotate(rot, new int[]{x, y, z}));
    }

    public List<BlockPos> doorLeaves() {
        return solve() ? List.copyOf(doorLeaves) : List.of();
    }

    public List<BlockPos> singleLeaves() {
        return solve() ? List.copyOf(singleLeaves) : List.of();
    }

    /** 15 组双扇门的世界坐标（每组两个门叶下半格）。 */
    public List<List<BlockPos>> pairsWorld() {
        if (!solve()) {
            return List.of();
        }
        List<List<BlockPos>> out = new ArrayList<>();
        for (int[][] pair : DOOR_PAIRS) {
            List<BlockPos> leaves = new ArrayList<>(pair.length);
            for (int[] l : pair) {
                leaves.add(toWorld(l));
            }
            out.add(leaves);
        }
        return out;
    }

    public List<BlockPos> hallPoints() {
        List<BlockPos> out = new ArrayList<>();
        for (int[] p : HALL_POINTS) {
            BlockPos w = toWorld(p);
            if (w != null) {
                out.add(w);
            }
        }
        return out;
    }

    public List<BlockPos> rallyPoints() {
        List<BlockPos> out = new ArrayList<>();
        for (int[] p : RALLY_POINTS) {
            BlockPos w = toWorld(p);
            if (w != null) {
                out.add(w);
            }
        }
        return out;
    }

    /** 该家的住户：有床或驻守锚点落在结构内、不是援军单位、未被玩家收服。 */
    public List<LumenCompanionEntity> residents() {
        List<LumenCompanionEntity> out = new ArrayList<>();
        for (LumenCompanionEntity mob : level.getEntitiesOfClass(LumenCompanionEntity.class, aabb(16))) {
            if (!mob.isAlive() || mob.isTame() || RaidRelief.marked(mob)) {
                continue;
            }
            var d = CompanionDuty.data(mob);
            BlockPos bed = CompanionDuty.pos(d, "Bed", level);
            BlockPos anchor = CompanionDuty.pos(d, "Anchor", level);
            if (contains(bed) || contains(anchor)) {
                out.add(mob);
            }
        }
        return out;
    }

    /** 结构内一扇铁门下半格的世界坐标集合。 */
    private Set<BlockPos> collectWorldDoors() {
        Set<BlockPos> out = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(box.minX() - 2, box.minY() - 1, box.minZ() - 2,
                box.maxX() + 2, box.maxY() + 1, box.maxZ() + 2)) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.IRON_DOOR) && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                out.add(p.immutable());
            }
        }
        return out;
    }

    private static BlockPos rotate(int[] r, int[] l) {
        return new BlockPos(r[0] * l[0] + r[1] * l[2], l[1], r[2] * l[0] + r[3] * l[2]);
    }

    /** 门下半格是否仍是铁门（玩家可能拆掉或替换）。 */
    public static boolean stillIronDoor(ServerLevel level, BlockPos lower) {
        BlockState st = level.getBlockState(lower);
        return st.is(Blocks.IRON_DOOR) && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    /** 门的两个下半格按 15 组配对，便于诊断输出。 */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("结构范围 " + box.minX() + "," + box.minY() + "," + box.minZ()
                + " → " + box.maxX() + "," + box.maxY() + "," + box.maxZ());
        if (!solve()) {
            lines.add("坐标反推：失败（铁门不足）");
            return lines;
        }
        lines.add("坐标反推：旋转矩阵 " + java.util.Arrays.toString(rot) + " 原点 " + zero
                + "，铁门匹配 " + matched + "/" + DOOR_LEAVES.length);
        List<LumenCompanionEntity> residents = residents();
        lines.add("住户 " + residents.size() + " 名");
        for (LumenCompanionEntity mob : residents) {
            var d = CompanionDuty.data(mob);
            lines.add("  " + mob.getName().getString() + " 位置 " + mob.blockPosition()
                    + " 床 " + CompanionDuty.pos(d, "Bed", level)
                    + " 驻守 " + d.getBoolean("Guard"));
        }
        StringBuilder hall = new StringBuilder("大厅点：");
        for (int i = 0; i < HALL_POINTS.length; i++) {
            BlockPos w = toWorld(HALL_POINTS[i]);
            hall.append(i).append('=').append(w).append(fit(w) ? "✓ " : "✗ ");
        }
        lines.add(hall.toString());
        StringBuilder rally = new StringBuilder("集结点：");
        for (int i = 0; i < RALLY_POINTS.length; i++) {
            BlockPos w = toWorld(RALLY_POINTS[i]);
            rally.append(i).append('=').append(w).append(fit(w) ? "✓ " : "✗ ");
        }
        lines.add(rally.toString());
        return lines;
    }

    /** 该点是否能站人（本体与上方空气、脚下实心）。 */
    public boolean fit(BlockPos p) {
        if (p == null) {
            return false;
        }
        return level.isEmptyBlock(p) && level.isEmptyBlock(p.above())
                && !level.isEmptyBlock(p.below());
    }

}
