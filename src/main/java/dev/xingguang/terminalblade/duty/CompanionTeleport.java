package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 随从的「跨区块 / 跨维度」跟随。
 *
 * <p>原版的跟随目标只在随从<b>自己 tick</b> 时才会把它拉回主人身边；随从所在区块一卸载，
 * 实体就不 tick 了，于是会出现「主人传送/换维度之后随从卡在原地」。</p>
 *
 * <p>这里每 20 tick 检查一次 {@link CompanionTracker}：如果随从离主人超过
 * {@link #LEASH_DISTANCE} 格（或不在同一维度）且当前没有加载，就把它最后所在的区块
 * 从磁盘读回来，再传送到主人身边（同维度直接传送，跨维度走
 * {@link Entity#changeDimension(DimensionTransition)}）。</p>
 *
 * <p>随从待过的区块必然已经生成过，所以读回区块不会触发地形生成；整段逻辑每 20 tick
 * 最多处理一只随从，避免主线程上出现集中加载。</p>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class CompanionTeleport {
    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final double LEASH_DISTANCE = 64.0D;

    /** 诊断用：同一只随从的失败日志最多每分钟一条，避免刷屏。 */
    private static final java.util.Map<UUID, Long> LAST_FAIL = new java.util.HashMap<>();
    private static boolean announced;

    private CompanionTeleport() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.overworld().getGameTime() % CHECK_INTERVAL_TICKS != 0L) {
            return;
        }
        if (!announced) {
            announced = true;
            TerminalBlade.LOGGER.info("[跟随] 跨区块跟随已启用（每 20 tick 检查一次）");
        }
        CompanionTracker tracker = CompanionTracker.get(server);
        if (tracker.entries().isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        if (now % 200L == 0L) {
            TerminalBlade.LOGGER.info("[跟随] 已登记随从 {} 只", tracker.entries().size());
        }
        // 遍历快照：处理过程中会改动 entries（传送后刷新位置、失效记录会被移除）。
        for (Map.Entry<UUID, CompanionTracker.Entry> entry : new ArrayList<>(tracker.entries().entrySet())) {
            try {
                rescue(server, tracker, entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                TerminalBlade.LOGGER.warn("[流明·星] 随从 {} 的跟随处理失败", entry.getKey(), e);
            }
        }
    }

    private static void fail(UUID id, MinecraftServer server, String reason) {
        long now = server.overworld().getGameTime();
        Long last = LAST_FAIL.get(id);
        if (last == null || now - last > 1200L) {
            LAST_FAIL.put(id, now);
            TerminalBlade.LOGGER.info("[跟随] 随从 {} 未能跟随：{}", id, reason);
        }
    }

    private static void rescue(MinecraftServer server, CompanionTracker tracker, UUID id,
                               CompanionTracker.Entry info) {
        // 主人让它坐下的随从留在原地，不强行拉走。
        if (info.sitting()) {
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(info.owner());
        if (owner == null) {
            return;
        }
        ServerLevel home = server.getLevel(info.dimension());
        if (home == null) {
            tracker.remove(id);
            return;
        }
        boolean otherDimension = owner.serverLevel() != home;
        if (!otherDimension
                && owner.blockPosition().distSqr(info.pos()) <= LEASH_DISTANCE * LEASH_DISTANCE) {
            return;
        }

        Entity entity = home.getEntities().get(id);
        boolean wasLoaded = entity != null;
        if (entity == null) {
            // 只读回已经生成过的区块；随从待过的位置一定生成过，不会在这里生成新地形。
            home.getChunk(info.pos().getX() >> 4, info.pos().getZ() >> 4);
            entity = home.getEntities().get(id);
        }
        if (!(entity instanceof LumenCompanionEntity pet) || !pet.isAlive()) {
            // 随从已经不在了（被删除/被其它模组清理），把登记记录一起清掉。
            fail(id, server, "区块 " + info.pos() + " 里找不到实体或已死亡，记录已清理");
            tracker.remove(id);
            return;
        }
        if (pet.isOrderedToSit()) {
            return;
        }

        ServerLevel target = owner.serverLevel();
        BlockPos spot = SafeSpots.find(target, owner.blockPosition());
        if (spot == null) {
            fail(id, server, "主人附近找不到安全落点（主人=" + owner.blockPosition() + "）");
            return;
        }
        if (target == home) {
            pet.teleportTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
            pet.setDeltaMovement(0.0D, 0.0D, 0.0D);
        } else {
            pet.changeDimension(new DimensionTransition(target, Vec3.atBottomCenterOf(spot), Vec3.ZERO,
                    owner.getYRot(), 0.0F, DimensionTransition.DO_NOTHING));
        }
        TerminalBlade.LOGGER.info("[流明·星] 随从跟随到主人身边：{} -> {}（区块{}）",
                info.pos(), spot, wasLoaded ? "已加载" : "从磁盘读回");
    }
}
