package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.TerminalBlade;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 自动援军触发：不再需要流明灯，高等级袭击达到条件时自动调用 {@link RaidRelief#call}。
 *
 * <p>条件与之前一致：钟附近有进行中的袭击、袭击之兆 ≥2、已刷出第 4 波、仍有袭击者。
 * 实际派遣/去重/撤离由 {@link RaidRelief} 与 {@link ReliefLedger} 负责。</p>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID)
public final class RaidReliefDirector {
    private RaidReliefDirector() {
    }

    @SubscribeEvent
    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            // 和流明灯完全同一套判定：先按玩家位置找袭击（getRaidAt），找不到再放宽到 128 格。
            Raid raid = CompanionDuty.raid(level, player.blockPosition());
            if (raid == null) {
                raid = level.getRaids().getNearbyRaid(player.blockPosition(), 128);
            }
            if (raid == null || !raid.isActive() || raid.isOver() || raid.isVictory() || raid.isLoss()) {
                continue;
            }
            // 不同 omen 等级的袭击总波数不同（通常 3~5 波）。
            // 自动援军按设计在第 4 波出场；若整场不足 4 波，退到最后一波（否则永远等不到）。
            // 想要"严格第 4 波、短袭击不自动派"就把 Math.min 去掉，直接写 4。
            int waves = raid.getNumGroups(level.getDifficulty());
            int triggerWave = Math.min(4, Math.max(1, waves));
            if (level.getGameTime() % 100 == 0) {
                TerminalBlade.LOGGER.info("[流明·星] 袭击检查 raid#{} omen={} wave={}/{} alive={} 触发波={} 玩家距中心={}",
                        raid.getId(), raid.getRaidOmenLevel(), raid.getGroupsSpawned(), waves,
                        raid.getTotalRaidersAlive(), triggerWave,
                        (int) Math.sqrt(player.distanceToSqr(raid.getCenter().getX() + 0.5,
                                raid.getCenter().getY(), raid.getCenter().getZ() + 0.5)));
            }
            if (raid.getGroupsSpawned() < triggerWave || raid.getTotalRaidersAlive() < 1) {
                continue;
            }
            if (ReliefLedger.get(level).sent(raid.getId())) {
                continue;
            }
            TerminalBlade.LOGGER.info("[流明·星] 自动求援 raid#{} omen={} wave={}/{} alive={}",
                    raid.getId(), raid.getRaidOmenLevel(), raid.getGroupsSpawned(), waves,
                    raid.getTotalRaidersAlive());
            RaidRelief.call(player);
            if (ReliefLedger.get(level).sent(raid.getId())) {
                TerminalBlade.LOGGER.info("[流明·星] 援军已进场 raid#{}", raid.getId());
            }
        }
    }
}
