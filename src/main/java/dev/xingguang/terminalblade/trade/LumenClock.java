package dev.xingguang.terminalblade.trade;

import dev.xingguang.terminalblade.TerminalBlade;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 世界级的"天序号"：每 20 tick 观察一次 {@code level.getDayTime()}，
 * 只要**跨过 24000 边界**或**时间往回跳**（{@code /time set} 会把它设成绝对值），就把序号 +1。
 *
 * <p>为什么要独立成世界时钟：之前是"打开商店时才观察"，于是
 * {@code /time set night}(13000) → 睡觉(24000) 这种往返会整个落在两次观察之间，
 * 等下次开界面时 {@code DayTime} 已经绕回同一个天数索引，判成"没跨天"。
 * 改成每 tick 持续观察后，任何跳变都会在 1 秒内被记下来。</p>
 *
 * <p>序号存在世界存档里，永久单调递增——即使时间被命令改来改去，只要发生过跳变就算过一天。</p>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID)
public final class LumenClock extends SavedData {
    private static final String FILE_ID = "mistletoe_lumen_clock";
    private static final SavedData.Factory<LumenClock> FACTORY =
            new SavedData.Factory<>(LumenClock::new, LumenClock::load, null);

    /** 观察间隔（tick）。20 tick = 1 秒，足够快地抓到一次跳变。 */
    private static final int OBSERVE_INTERVAL = 20;

    private static final long UNSET = Long.MIN_VALUE;

    /** 上次观察到的原始 dayTime。 */
    private long lastTime = UNSET;
    /** 累计"天跳变"次数，单调递增。 */
    private long daySeq;

    public static LumenClock of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public static LumenClock load(CompoundTag tag, HolderLookup.Provider provider) {
        LumenClock clock = new LumenClock();
        if (tag.contains("LastTime")) {
            clock.lastTime = tag.getLong("LastTime");
        }
        clock.daySeq = tag.getLong("DaySeq");
        return clock;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLong("LastTime", this.lastTime);
        tag.putLong("DaySeq", this.daySeq);
        return tag;
    }

    public long daySeq() {
        return this.daySeq;
    }

    /** 推进观察；返回 true 表示这次观察发现了"天变了"。 */
    public boolean tick(long dayTime) {
        if (this.lastTime == UNSET) {
            this.lastTime = dayTime;
            this.setDirty();
            return false;
        }
        boolean backwards = dayTime < this.lastTime;
        boolean crossed = dayTime / LumenShop.DAY_TICKS != this.lastTime / LumenShop.DAY_TICKS;
        this.lastTime = dayTime;
        if (!backwards && !crossed) {
            return false;
        }
        this.daySeq++;
        this.setDirty();
        return true;
    }

    @SubscribeEvent
    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getGameTime() % OBSERVE_INTERVAL != 0) {
            return;
        }
        LumenClock.of(level).tick(level.getDayTime());
    }

    private LumenClock() {
    }
}
