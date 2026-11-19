package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「家里开袭击」时把 15 组卧室铁门打开，让住户出来迎战，袭击结束后再恢复。
 *
 * <p>规则：</p>
 * <ul>
 *     <li>只在袭击中心落在 {@code mistletoe:lumen_home} 结构内时生效；</li>
 *     <li>只操纵结构自带的 15 组双扇卧室铁门；按钮、拉杆、4 扇单扇门和玩家后加的门都不碰；</li>
 *     <li>第一波倒计时期间唤醒住户、把它们送到大厅边缘集结点；</li>
 *     <li>整场袭击期间门一直开着（否则住客一走出 8 格门就关，看起来就是一开一关）；</li>
 *     <li>袭击结束（不再有袭击中心落在这个家）后释放持有，按当时的红石供电状态恢复。</li>
 * </ul>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID)
public final class HomeRally {
    /** 每个维度里，哪些袭击 id 正在让家里的门保持打开。 */
    private static final Map<ResourceKey<Level>, Set<Integer>> RALLIED = new HashMap<>();
    /** 下一次重新下达移动指令的游戏刻。 */
    private static final Map<java.util.UUID, Long> NEXT_MOVE = new HashMap<>();
    /** 每个住户本轮被分配的集结点。 */
    private static final Map<java.util.UUID, BlockPos> RALLY_TARGET = new HashMap<>();

    private static final int CHECK_INTERVAL = 10;
    private static final long HOLD_SAFETY = 12000L;
    private static final int ROOM_BFS_CAP = 1200;

    private HomeRally() {
    }

    @SubscribeEvent
    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        HomeDoors.tick(level);
        if (level.getGameTime() % CHECK_INTERVAL != 0) {
            return;
        }
        Set<Integer> active = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            Raid raid = CompanionDuty.raid(level, player.blockPosition());
            if (raid == null || !raid.isActive() || raid.isOver() || raid.isStopped()) {
                continue;
            }
            LumenHome home = LumenHome.at(level, raid.getCenter());
            if (home == null || !home.contains(raid.getCenter()) || !home.solvable()) {
                continue;
            }
            active.add(raid.getId());
            String tag = "raid:" + level.dimension().location() + ":" + raid.getId();
            holdBedroomDoors(level, home, tag);
            if (RALLIED.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(raid.getId())) {
                rally(level, raid, home);
            } else if (raid.getGroupsSpawned() == 0) {
                keepMoving(level, home);
            }
        }
        // 袭击结束/离开：释放这些袭击的持有，门在下一 tick 按供电状态恢复。
        Set<Integer> known = RALLIED.get(level.dimension());
        if (known != null && !known.isEmpty()) {
            for (Integer id : new HashSet<>(known)) {
                if (!active.contains(id)) {
                    known.remove(id);
                    HomeDoors.releaseTag("raid:" + level.dimension().location() + ":" + id);
                    TerminalBlade.LOGGER.info("[流明·星] 袭击 raid#{} 结束，收起卧室门", id);
                }
            }
        }
    }

    /** 把结构自带的 34 个门叶（15 组双扇 + 4 扇单扇）全部按标签持有。 */
    private static void holdBedroomDoors(ServerLevel level, LumenHome home, String tag) {
        long deadline = level.getGameTime() + HOLD_SAFETY;
        for (List<BlockPos> pair : home.pairsWorld()) {
            for (BlockPos leaf : pair) {
                HomeDoors.hold(level, leaf, tag, deadline);
            }
        }
        for (BlockPos single : home.singleLeaves()) {
            HomeDoors.hold(level, single, tag, deadline);
        }
    }

    /** 第一波前：唤醒住户，送到大厅边缘。 */
    private static void rally(ServerLevel level, Raid raid, LumenHome home) {
        List<BlockPos> points = home.rallyPoints();
        if (points.isEmpty()) {
            return;
        }
        List<LumenCompanionEntity> residents = home.residents();
        int moved = 0;
        int i = 0;
        for (LumenCompanionEntity mob : residents) {
            if (mob.isSleeping()) {
                mob.stopSleeping();
            }
            BlockPos target = points.get(i++ % points.size());
            RALLY_TARGET.put(mob.getUUID(), target);
            mob.getNavigation().stop();
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            NEXT_MOVE.put(mob.getUUID(), level.getGameTime() + 20);
            moved++;
        }
        TerminalBlade.LOGGER.info("[流明·星] 集结 raid#{} 住户={} 中心={}",
                raid.getId(), moved, raid.getCenter());
    }

    /** 第一波刷出前，如果还有住户没走到集结点，继续催；已到位的不打扰。 */
    private static void keepMoving(ServerLevel level, LumenHome home) {
        long now = level.getGameTime();
        for (LumenCompanionEntity mob : home.residents()) {
            Long next = NEXT_MOVE.get(mob.getUUID());
            if (next != null && now < next) {
                continue;
            }
            BlockPos target = RALLY_TARGET.get(mob.getUUID());
            if (target == null) {
                continue;
            }
            if (mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
                NEXT_MOVE.put(mob.getUUID(), now + 40);
            } else {
                NEXT_MOVE.put(mob.getUUID(), now + 20);
            }
        }
    }

    /**
     * 从随从所在位置做有界 flood-fill，找它所在房间的那扇双扇铁门（1~2 个下半格）。
     *
     * <p>只用于诊断输出；开门本身不再依赖它——家里被袭击时 15 组卧室门全部打开。</p>
     */
    static List<BlockPos> roomDoor(ServerLevel level, LumenHome home, BlockPos from) {
        if (from == null || !home.contains(from)) {
            return List.of();
        }
        Set<BlockPos> seen = new HashSet<>();
        Set<BlockPos> doors = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(from.immutable());
        while (!queue.isEmpty() && seen.size() <= ROOM_BFS_CAP) {
            BlockPos p = queue.poll();
            if (!seen.add(p)) {
                continue;
            }
            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                if (!home.contains(n) || seen.contains(n)) {
                    continue;
                }
                BlockState st = level.getBlockState(n);
                if (st.is(Blocks.IRON_DOOR) && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    doors.add(n.immutable());
                    continue;
                }
                if (st.getCollisionShape(level, n).isEmpty()
                        && level.getBlockState(n.above()).getCollisionShape(level, n.above()).isEmpty()) {
                    queue.add(n.immutable());
                }
            }
        }
        if (doors.isEmpty() || doors.size() > 2) {
            return List.of();
        }
        return List.copyOf(doors);
    }

    /** 诊断用：当前住户对门的映射（世界坐标），供 /mistletoe home 输出。 */
    public static Map<String, String> diagnose(ServerLevel level, LumenHome home) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (LumenCompanionEntity mob : home.residents()) {
            List<BlockPos> door = roomDoor(level, home, mob.blockPosition());
            out.put(mob.getName().getString(), door.isEmpty() ? "无（在大厅/未封闭）" : door.toString());
        }
        return out;
    }
}
