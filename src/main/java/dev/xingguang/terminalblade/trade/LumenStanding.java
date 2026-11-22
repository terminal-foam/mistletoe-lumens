package dev.xingguang.terminalblade.trade;

import dev.xingguang.terminalblade.TerminalBlade;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * 玩家对**某一位**流明的熟络度：阶段 + 经验条。
 *
 * <p>作用域：**每只流明各自一套，按玩家分别记录**。新刷一只就从 Day 1 开始。</p>
 *
 * <p><b>"天"由 {@link LumenClock} 提供</b>：那是一个每 20 tick 持续观察
 * {@code level.getDayTime()} 的世界级单调序号（跨过 24000 边界或时间往回跳都算一次）。
 * 这里不自己算天，只比较"满条时记下的世界序号"和"当前世界序号"——
 * 这样睡觉、{@code /time set night}、以及两者叠加都不会漏判。</p>
 *
 * <p>规则：买卖都加经验；经验条满之后要**跨过一天**才进入下一档，进档时经验条清零。
 * 阶段决定 {@link LumenShop} 里哪些货会显示（没解锁的完全不显示）。</p>
 */
public final class LumenStanding {
    private static final String SHOPS_KEY = "mistletoe_lumen_shops";
    private static final String STAGE = "stage";
    private static final String XP = "xp";
    /** 满条时记下的"世界天序号 + 1"；0 = 当前没在等。 */
    private static final String ARMED_SEQ = "armedSeq";

    /** 达到下一阶段需要的经验，下标 = 当前阶段（1..6）。 */
    private static final int[] NEXT_AT = {0, 8, 18, 30, 44, 60, 78};

    public static final int MAX_STAGE = 7;

    private static final CompoundTag EMPTY = new CompoundTag();

    private LumenStanding() {
    }

    // ── 存储 ────────────────────────────────────────────────────────────

    private static String key(Player player, UUID merchant) {
        return player.getUUID() + "|" + merchant;
    }

    /**
     * 把旧版本存在 {@code player.getPersistentData()} 顶层的熟络度搬进世界存档。
     *
     * <p>NeoForge 在玩家重生/换维度时只复制 {@code PlayerPersisted} 子键，顶层自定义数据会丢，
     * 这也是"死亡重生后商店退回 Day 1"的根因。迁移后玩家身上不再保留这份数据。</p>
     */
    private static void migrate(Player player, MinecraftServer server) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(SHOPS_KEY)) {
            return;
        }
        CompoundTag shops = data.getCompound(SHOPS_KEY);
        LumenStandingData store = LumenStandingData.get(server);
        String prefix = player.getUUID() + "|";
        int moved = 0;
        for (String merchant : shops.getAllKeys()) {
            CompoundTag value = shops.getCompound(merchant).copy();
            for (String legacy : LEGACY_KEYS) {
                value.remove(legacy);
            }
            if (!value.isEmpty()) {
                store.putIfAbsent(prefix + merchant, value);
                moved++;
            }
        }
        data.remove(SHOPS_KEY);
        if (moved > 0) {
            TerminalBlade.LOGGER.info("[流明·星] 已迁移 {} 条商店熟络度到世界存档", moved);
        }
    }

    /** 只读取用：不存在就返回一个临时空标签（不挂到存档里）。 */
    private static CompoundTag peek(Player player, UUID merchant) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return EMPTY;
        }
        migrate(player, server);
        return LumenStandingData.get(server).peek(key(player, merchant));
    }

    /** 写入用：不存在就建好并挂进世界存档。 */
    private static CompoundTag of(Player player, UUID merchant) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return EMPTY;
        }
        migrate(player, server);
        return LumenStandingData.get(server).of(key(player, merchant));
    }

    /** 当前世界的"天序号"（由 {@link LumenClock} 持续维护，统一取主世界时钟）。 */
    private static long worldDay(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0L;
        }
        return LumenClock.of(server.overworld()).daySeq();
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    public static int stage(Player player, UUID merchant) {
        int stage = peek(player, merchant).getInt(STAGE);
        return stage < 1 ? 1 : Math.min(stage, MAX_STAGE);
    }

    public static int xp(Player player, UUID merchant) {
        return Math.max(0, peek(player, merchant).getInt(XP));
    }

    /** 本阶段经验条上限；满级返回 0。 */
    public static int xpNeeded(Player player, UUID merchant) {
        int stage = stage(player, merchant);
        return stage >= MAX_STAGE ? 0 : NEXT_AT[stage];
    }

    /** 经验条是否已满、正在等跨过一天。 */
    public static boolean waiting(Player player, UUID merchant) {
        return peek(player, merchant).getLong(ARMED_SEQ) != 0L;
    }

    // ── 变更 ────────────────────────────────────────────────────────────

    /**
     * 加经验（买东西、卖东西都算）。
     *
     * <p>顺序很关键：**先结算可能已经到期的等待（跨天），再发这次的经验**，
     * 否则跨天之后的第一笔成交会走"进档 → 经验清零"，白白吞掉这 1 点经验。</p>
     */
    public static void addXp(Player player, UUID merchant, int amount) {
        if (stage(player, merchant) >= MAX_STAGE) {
            return;
        }
        advanceIfDue(player, merchant);
        if (stage(player, merchant) >= MAX_STAGE) {
            return;
        }
        int need = xpNeeded(player, merchant);
        // 封顶在本阶段上限：等跨天期间继续成交不会堆积无意义经验
        int next = Math.min(need, xp(player, merchant) + amount);
        of(player, merchant).putInt(XP, next);
        if (next >= need) {
            advanceIfDue(player, merchant, true);
        }
    }

    /** 懒结算入口（开界面时调用）。 */
    public static void advanceIfDue(Player player, UUID merchant) {
        advanceIfDue(player, merchant, false);
    }

    /** 旧版本（2.6.0 之前）遗留的键：曾经用绝对天数/到期日记录"等一天"。 */
    private static final String[] LEGACY_KEYS = {"lastDay", "daySeq", "dueDay", "due"};

    /**
     * 清掉旧存档里已经不再使用的键（打开商店时顺手做一次，幂等）。
     *
     * <p>{@code 2.6.0} 起"天"改由 {@link LumenClock} 世界时钟维护，
     * 玩家数据里只剩 {@code stage} / {@code xp} / {@code armedSeq} 三个键。</p>
     */
    public static void pruneLegacy(Player player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            migrate(player, server);
        }
    }

    /**
     * 结算进档：经验条满 且 世界天序号已经跨过一天 → 升一档、经验清零。
     *
     * @param justFilled 是否刚在这次调用里加满经验（true 才会开始等"跨过一天"）
     */
    private static void advanceIfDue(Player player, UUID merchant, boolean justFilled) {
        long worldDay = worldDay(player);
        int stage = stage(player, merchant);
        if (stage >= MAX_STAGE) {
            return;
        }
        long armed = peek(player, merchant).getLong(ARMED_SEQ);
        if (armed == 0L) {
            if (xp(player, merchant) < xpNeeded(player, merchant)) {
                return;
            }
            // 满条：从现在起等下一个"世界天序号"
            armed = worldDay + 1L;
            of(player, merchant).putLong(ARMED_SEQ, armed);
            if (justFilled) {
                dev.xingguang.terminalblade.TerminalBlade.LOGGER.info(
                        "[流明·星] 经验条已满（stage {} / xp {}）：跨过一天后进档（当前天序号 {}）",
                        stage, xp(player, merchant), worldDay);
            }
            return;
        }
        if (armed > worldDay + 1L) {
            // 时钟被重置（新世界 / 存档迁移）或旧数据过大：重新对齐，别让玩家永久卡住
            armed = worldDay + 1L;
            of(player, merchant).putLong(ARMED_SEQ, armed);
            dev.xingguang.terminalblade.TerminalBlade.LOGGER.info(
                    "[流明·星] 等待目标超出当前天序号，重新对齐到 {}", armed);
            return;
        }
        if (worldDay >= armed) {
            CompoundTag shop = of(player, merchant);
            shop.putInt(STAGE, stage + 1);
            shop.putInt(XP, 0);
            shop.putLong(ARMED_SEQ, 0L);
            dev.xingguang.terminalblade.TerminalBlade.LOGGER.info(
                    "[流明·星] 进档：stage {} -> {}（天序号 {}）", stage, stage + 1, worldDay);
        }
    }

    /** `/mistletoe shop` 用的一行状态，方便排查"为什么还没进档"。 */
    public static String describe(Player player, UUID merchant) {
        int stage = stage(player, merchant);
        int need = xpNeeded(player, merchant);
        int xp = xp(player, merchant);
        long worldDay = worldDay(player);
        long armed = peek(player, merchant).getLong(ARMED_SEQ);
        StringBuilder sb = new StringBuilder();
        sb.append("Day ").append(stage).append("  经验 ").append(xp);
        if (need > 0) {
            sb.append(" / ").append(need);
        } else {
            sb.append("（已满级）");
        }
        sb.append("  世界天序号=").append(worldDay);
        if (armed != 0L) {
            sb.append("  等待中：天序号到 ").append(armed).append(" 进档（睡觉/调时间都会推进）");
        } else if (need > 0 && xp >= need) {
            sb.append("  （经验已满，跨过一天就进档）");
        }
        return sb.toString();
    }

    /**
     * 经验条的"映射段"：借用原版村民经验带（等级 1~4 的 min..max），
     * 把本阶段进度等比压进去。
     *
     * <p>为什么要选段：原版经验条是 `(当前xp − 等级下限) / 区间宽度` 画的，
     * 而"这一笔能加多少"的白段是 `交易xp / 区间宽度`。我们要让白段正好等于
     * `1 / 本阶段门槛`，就得让 `交易xp / 宽度 ≈ 1 / 门槛`，所以按阶段挑一段宽度最合适的经验带。</p>
     *
     * @param level 要发给客户端的"商人等级"（必须是 1~4，5 级原版不画条）
     * @param min   该等级的经验下限
     * @param width 该等级区间宽度
     * @param unit  每条交易在该区间里占多少（就是 offer 的 xp）
     */
    public record Bar(int level, int min, int width, int unit) {
    }

    public static Bar barFor(int stage) {
        int need = stage >= MAX_STAGE ? 0 : NEXT_AT[Math.max(1, stage)];
        if (need <= 0) {
            return new Bar(1, 0, 10, 0); // 满级：条不再有意义
        }
        int bestLevel = 1;
        int bestUnit = 1;
        double bestErr = Double.MAX_VALUE;
        for (int level = 1; level <= 4; level++) {
            int min = net.minecraft.world.entity.npc.VillagerData.getMinXpPerLevel(level);
            int max = net.minecraft.world.entity.npc.VillagerData.getMaxXpPerLevel(level);
            int width = max - min;
            if (width <= 0) {
                continue;
            }
            int unit = Math.max(1, Math.round((float) width / need));
            // 白段宽度 = unit/width，理想是 1/need；用相对误差比较
            double err = Math.abs((double) unit / width - 1.0D / need) * need;
            if (err < bestErr) {
                bestErr = err;
                bestLevel = level;
                bestUnit = unit;
            }
        }
        int min = net.minecraft.world.entity.npc.VillagerData.getMinXpPerLevel(bestLevel);
        int max = net.minecraft.world.entity.npc.VillagerData.getMaxXpPerLevel(bestLevel);
        return new Bar(bestLevel, min, max - min, bestUnit);
    }

    /**
     * 经验条上要发给客户端的**绝对** xp 值：把「本阶段进度」等比映射到该阶段的经验带里。
     * 满条在等跨天时直接顶到区间上限，绿条看起来是满的。
     */
    public static int barValue(Player player, UUID merchant) {
        int need = xpNeeded(player, merchant);
        if (need <= 0) {
            return 0;
        }
        Bar bar = barFor(stage(player, merchant));
        int xp = waiting(player, merchant) ? need : xp(player, merchant);
        return bar.min() + Math.round((float) bar.width() * xp / need);
    }
}
