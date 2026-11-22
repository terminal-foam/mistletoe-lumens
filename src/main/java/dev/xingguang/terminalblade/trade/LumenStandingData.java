package dev.xingguang.terminalblade.trade;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 玩家对每只流明的熟络度（Day 阶段 / 经验 / 等待进档），存在<b>世界存档</b>里。
 *
 * <p>以前这些数据放在 {@code player.getPersistentData()} 的顶层，而 NeoForge 在玩家重生时
 * 只复制 {@code PlayerPersisted} 这一个子键，其余自定义数据会丢——所以死亡重生后
 * 商店会退回 Day 1。放到世界存档（按「玩家 UUID | 流明 UUID」分别记录）就不受死亡、
 * 换维度、重进游戏影响。</p>
 */
public final class LumenStandingData extends SavedData {
    private static final String FILE_ID = "mistletoe_lumen_standing";
    private static final CompoundTag EMPTY = new CompoundTag();

    private final Map<String, CompoundTag> shops = new LinkedHashMap<>();

    private LumenStandingData() {
    }

    public static LumenStandingData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(LumenStandingData::new, LumenStandingData::load), FILE_ID);
    }

    private static LumenStandingData load(CompoundTag tag, HolderLookup.Provider provider) {
        LumenStandingData data = new LumenStandingData();
        CompoundTag all = tag.getCompound("Shops");
        for (String key : all.getAllKeys()) {
            data.shops.put(key, all.getCompound(key).copy());
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag all = new CompoundTag();
        this.shops.forEach((key, value) -> all.put(key, value.copy()));
        tag.put("Shops", all);
        return tag;
    }

    /** 只读取：不存在返回一个临时空标签，不会在存档里留下空条目。 */
    public CompoundTag peek(String key) {
        CompoundTag value = this.shops.get(key);
        return value == null ? EMPTY : value;
    }

    /** 写入：不存在就建好并挂进存档。返回的标签就是存档里那一个，可以直接改。 */
    public CompoundTag of(String key) {
        CompoundTag value = this.shops.get(key);
        if (value == null) {
            value = new CompoundTag();
            this.shops.put(key, value);
        }
        setDirty();
        return value;
    }

    /** 迁移旧数据用：只在目标键不存在时写入，避免覆盖更新的存档。 */
    public void putIfAbsent(String key, CompoundTag value) {
        if (!this.shops.containsKey(key)) {
            this.shops.put(key, value.copy());
            setDirty();
        }
    }

    public void remove(String key) {
        if (this.shops.remove(key) != null) {
            setDirty();
        }
    }

    public Map<String, CompoundTag> shops() {
        return this.shops;
    }
}
