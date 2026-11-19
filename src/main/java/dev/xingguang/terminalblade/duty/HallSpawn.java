package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.mixin.RaidCooldownAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「家里的袭击」把每一波刷在结构自带的大厅点上。
 *
 * <p>只替换 {@code Raid.findRandomSpawnPos} 的返回位置，整波仍由原版 {@code spawnGroup}
 * 生成（号角、队长、波数、袭击条与胜负结算都不变）。三个点按 {@code groupsSpawned}
 * 轮换；选中的点要求 2×2 底面、3 格净空。三个点都堵时回退原版刷点并提示一次。</p>
 *
 * <p>预警圈在波次冷却剩 40 tick 时开始画，用的和真正刷怪时是同一个选择函数，
 * 所以「圈在哪、怪就刷在哪」（除非玩家在最后 2 秒里又把点堵了）。</p>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID)
public final class HallSpawn {
    private static final Set<String> BLOCKED_LOGGED = new HashSet<>();
    private static final int WARN_TICKS = 40;

    private HallSpawn() {
    }

    /** 由 {@code RaidSpawnMixin} 调用：返回本波要用的世界坐标；null = 不接管（回退原版）。 */
    public static BlockPos override(Raid raid) {
        if (raid == null || !(raid.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        try {
            return pick(raid, level);
        } catch (RuntimeException e) {
            // 这个方法是 mixin 从原版 Raid.tick 里调进来的，绝不能抛出去。
            if (FAILURES++ % 20 == 0) {
                TerminalBlade.LOGGER.error("[流明·星] 大厅刷怪选点异常，本波回退原版刷点", e);
            }
            return null;
        }
    }

    private static int FAILURES = 0;

    private static BlockPos pick(Raid raid, ServerLevel level) {
        LumenHome home = LumenHome.at(level, raid.getCenter());
        if (home == null || !home.contains(raid.getCenter()) || !home.solvable()) {
            return null;
        }
        List<BlockPos> points = home.hallPoints();
        if (points.isEmpty()) {
            return null;
        }
        int start = Math.max(0, raid.getGroupsSpawned());
        for (int i = 0; i < points.size(); i++) {
            BlockPos p = points.get((start + i) % points.size());
            if (fits(level, p)) {
                return p;
            }
        }
        String key = level.dimension().location() + ":" + raid.getId() + ":" + raid.getGroupsSpawned();
        if (BLOCKED_LOGGED.add(key)) {
            if (BLOCKED_LOGGED.size() > 256) {
                BLOCKED_LOGGED.clear();
            }
            TerminalBlade.LOGGER.warn("[流明·星] 大厅刷怪点全部受阻，raid#{} 第 {} 波回退原版刷点",
                    raid.getId(), raid.getGroupsSpawned() + 1);
        }
        return null;
    }

    /** 2×2 底面、3 格净空、脚下实心（劫掠兽 1.95×2.2 也放得下）。 */
    static boolean fits(ServerLevel level, BlockPos p) {
        if (p == null) {
            return false;
        }
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                BlockPos base = p.offset(dx, 0, dz);
                for (int dy = 0; dy <= 2; dy++) {
                    if (!level.isEmptyBlock(base.above(dy))) {
                        return false;
                    }
                }
                if (level.isEmptyBlock(base.below())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 波次冷却剩 40 tick 开始画预警圈（与真正刷怪用同一个选择函数）。 */
    @SubscribeEvent
    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getGameTime() % 5 != 0) {
            return;
        }
        try {
            warn(level);
        } catch (RuntimeException e) {
            if (FAILURES++ % 20 == 0) {
                TerminalBlade.LOGGER.error("[流明·星] 大厅刷点预警异常（已跳过本 tick）", e);
            }
        }
    }

    private static void warn(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            Raid raid = CompanionDuty.raid(level, player.blockPosition());
            if (raid == null || !raid.isActive() || raid.isOver() || raid.isStopped()) {
                continue;
            }
            int cooldown = ((RaidCooldownAccessor) (Object) raid).mistletoe$cooldownTicks();
            if (cooldown <= 0 || cooldown > WARN_TICKS) {
                continue;
            }
            BlockPos point = override(raid);
            if (point == null) {
                continue;
            }
            ring(level, point);
            if (cooldown == WARN_TICKS && player.distanceToSqr(
                    raid.getCenter().getX() + 0.5, raid.getCenter().getY() + 0.5, raid.getCenter().getZ() + 0.5) <= 96 * 96) {
                player.displayClientMessage(Component.literal("§b[流明·星]§r 本波在大厅中央刷出 · "
                        + point.getX() + ", " + point.getY() + ", " + point.getZ()), true);
            }
        }
    }

    private static void ring(ServerLevel level, BlockPos p) {
        level.sendParticles(ParticleTypes.END_ROD,
                p.getX() + 0.5, p.getY() + 0.1, p.getZ() + 0.5,
                12, 1.4, 0.05, 1.4, 0.0);
    }
}
