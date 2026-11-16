package dev.xingguang.terminalblade.trade;

import dev.xingguang.terminalblade.registry.ModItems;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ItemLike;

/**
 * 流明商店的货架：15 条交易、7 个解锁阶段。
 *
 * <p><b>报价规则</b>：表里的区间是「不同流明之间的差价」。同一位流明的报价在出生时
 * 按自己的 UUID 掷一次，此后**永久固定**——不是每天重新报价，补货也不重掷。</p>
 *
 * <p><b>解锁规则</b>：阶段 &lt;= 玩家当前阶段的货才出现，没解锁的**完全不显示**；
 * 已解锁的货全部保留。</p>
 *
 * <p>红宝石 {@code mistletoe:ruby} 是她们的货币。</p>
 */
public final class LumenShop {
    /**
     * 每条货每天可成交的次数；隔一个游戏日补满，价格不变。
     *
     * <p>每阶段当天可用次数 = 「已解锁行数 × 本条数」，对比该阶段经验门槛：
     * stage 1: 3×10=30≥8、2: 5×10=50≥18、3: 7×10=70≥30、4: 9×10=90≥44、
     * 5: 11×10=110≥60、6: 13×10=130≥78 —— 都有一倍以上余量，
     * 所以每档都能在当天填满经验条，还能顺手换点货。
     * 想让节奏更松就调成 12（13×12=156）。</p>
     */
    public static final int STOCK_PER_LINE = 10;

    /** 一个游戏日 = 24000 tick。 */
    public static final int DAY_TICKS = 24000;

    public static int lineCount() {
        return lines().size();
    }

    /** 一条货的数量区间。 */
    private record Spec(ItemLike item, int min, int max) {
        int roll(Random random) {
            return min >= max ? min : min + random.nextInt(max - min + 1);
        }
    }

    /** 收货结果：普通物品，或者一把刀。 */
    private record Reward(Spec item, boolean blade) {
        static Reward of(ItemLike item, int min, int max) {
            return new Reward(new Spec(item, min, max), false);
        }

        /** 固定 1 件、由 {@link LumenBlades} 现场打造。 */
        static Reward bladeReward() {
            return new Reward(null, true);
        }
    }

    /** 一行货。stage = 从哪个解锁阶段开始出现。 */
    private record Line(int stage, Spec costA, Spec costB, Reward reward) {
    }

    private static Spec spec(ItemLike item, int min, int max) {
        return new Spec(item, min, max);
    }

    /** 玩家付货（买单）：costB 为 null 表示只要一样东西。 */
    private static Line buy(int stage, Spec costA, Spec costB, Reward reward) {
        return new Line(stage, costA, costB, reward);
    }

    private static List<Line> lines;

    /**
     * 15 条交易。改价格、加条目、调解锁阶段都只动这张表。
     *
     * <p>注意：原版 {@link ItemCost} 只认单个物品、不支持物品标签，所以「原木」只能
     * 指定一种（现在用橡木原木）。要收全部木材得每种各写一行。</p>
     */
    private static List<Line> lines() {
        if (lines == null) {
            lines = List.of(
                    // ── Day 1 ──
                    buy(1, spec(Items.OAK_LOG, 14, 18), null, Reward.of(ModItems.RUBY.get(), 1, 1)),
                    buy(1, spec(Items.WHEAT, 18, 22), null, Reward.of(ModItems.RUBY.get(), 1, 1)),
                    buy(1, spec(ModItems.RUBY.get(), 1, 1), null, Reward.of(Items.COAL, 6, 6)),

                    // ── Day 2 ──
                    buy(2, spec(Items.SUGAR, 10, 14), null, Reward.of(ModItems.RUBY.get(), 1, 1)),
                    buy(2, spec(ModItems.RUBY.get(), 1, 1), null, Reward.of(Items.IRON_INGOT, 3, 3)),
                    buy(2, spec(ModItems.RUBY.get(), 1, 2), null, Reward.of(Items.BONE, 3, 5)),
                    buy(2, spec(ModItems.RUBY.get(), 1, 2), null, Reward.of(Items.STRING, 2, 4)),

                    // ── Day 3 ──
                    buy(3, spec(Items.PORKCHOP, 10, 14), null, Reward.of(ModItems.RUBY.get(), 2, 2)),
                    buy(3, spec(ModItems.RUBY.get(), 1, 1), null, Reward.of(Items.REDSTONE, 6, 6)),
                    buy(3, spec(ModItems.RUBY.get(), 1, 2), null, Reward.of(Items.PUMPKIN_PIE, 2, 3)),

                    // ── Day 4 ──
                    buy(4, spec(Items.EGG, 14, 18), null, Reward.of(ModItems.RUBY.get(), 1, 1)),
                    buy(4, spec(ModItems.RUBY.get(), 1, 2), null, Reward.of(Items.GUNPOWDER, 4, 4)),

                    // ── Day 5 ──（原「烈焰棒」已按需求移除）
                    buy(5, spec(ModItems.RUBY.get(), 2, 3), null, Reward.of(Items.GOLD_INGOT, 3, 3)),

                    // ── Day 6 ──
                    buy(6, spec(Items.TOTEM_OF_UNDYING, 1, 1), null, Reward.of(ModItems.RUBY.get(), 4, 6)),
                    buy(6, spec(ModItems.RUBY.get(), 2, 3), null, Reward.of(Items.ENDER_PEARL, 1, 1)),

                    // ── Day 7 ──
                    buy(7, spec(Items.NETHER_STAR, 2, 2), spec(ModItems.RUBY.get(), 5, 7), Reward.bladeReward()),
                    buy(7, spec(Items.LANTERN, 1, 1), spec(ModItems.RUBY.get(), 3, 5),
                            Reward.of(ModItems.LUMEN_LAMP.get(), 1, 1)));
        }
        return lines;
    }

    /**
     * 按「流明个体」掷出这位的固定报价。
     *
     * <p>只用实体 UUID 做种子，所以同一只流明每次打开都是同一套数字，
     * 不需要把价格写进存档。</p>
     */
    private static int roll(UUID id, int lineIndex, int fieldIndex, Spec spec) {
        long seed = id.getLeastSignificantBits() * 31L + id.getMostSignificantBits()
                + lineIndex * 977L + fieldIndex * 131L;
        return spec.roll(new Random(seed));
    }

    /**
     * 造出该玩家此刻能看到的货架。
     *
     * @param usedPerLine 每行已成交次数（长度 = {@link #lineCount()}），
     *                    用来把库存扣减反映到界面上
     * @param lineIds     输出：界面里第 i 条货对应表里的第几行
     */
    public static MerchantOffers offersFor(ServerLevel level, UUID merchantId, int stage,
                                           int[] usedPerLine, List<Integer> lineIds) {
        MerchantOffers offers = new MerchantOffers();
        lineIds.clear();
        List<Line> table = lines();
        // 每条交易在经验条里占多少，必须跟着当前阶段的门槛走，
        // 否则界面上的白段（这一笔能加多少）会和绿段对不上。
        int offerXp = LumenStanding.barFor(stage).unit();
        for (int i = 0; i < table.size(); i++) {
            Line line = table.get(i);
            if (line.stage() > stage) {
                continue; // 没解锁的货完全不显示
            }
            int countA = roll(merchantId, i, 0, line.costA());
            ItemCost costA = new ItemCost(line.costA().item(), countA);
            ItemCost costB = null;
            if (line.costB() != null) {
                costB = new ItemCost(line.costB().item(), roll(merchantId, i, 1, line.costB()));
            }
            ItemStack result = makeReward(level, merchantId, i, line.reward());
            MerchantOffer offer = new MerchantOffer(costA,
                    costB == null ? Optional.empty() : Optional.of(costB),
                    result, STOCK_PER_LINE, offerXp, 0.0F);
            // 把已用次数补回去，界面就会显示剩余库存
            int used = usedPerLine == null || i >= usedPerLine.length ? 0 : usedPerLine[i];
            for (int u = 0; u < used; u++) {
                offer.increaseUses();
            }
            offers.add(offer);
            lineIds.add(i);
        }
        return offers;
    }

    private static ItemStack makeReward(ServerLevel level, UUID merchantId, int lineIndex, Reward reward) {
        if (reward.blade()) {
            return LumenBlades.firstSnow(level);
        }
        Spec spec = reward.item();
        return new ItemStack(spec.item(), roll(merchantId, lineIndex, 2, spec));
    }
}
