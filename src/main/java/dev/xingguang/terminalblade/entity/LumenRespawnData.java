package dev.xingguang.terminalblade.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 随从「待复活」队列的持久化存储。
 *
 * <p>旧实现只放在内存 List 里，停服时清空；如果随从死了还没到复活时间就关档，
 * 再进档就永久少了这只随从。这里改用世界存档的 SavedData，写入主世界
 * {@code data/mistletoe_respawns.dat}，重启后按原定 tick 继续复活。</p>
 */
public final class LumenRespawnData extends SavedData {
    private static final String FILE_ID = "mistletoe_respawns";

    public record Entry(long dueTick, String name) {
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    public static LumenRespawnData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(LumenRespawnData::new, LumenRespawnData::load), FILE_ID);
    }

    public static LumenRespawnData load(CompoundTag tag, HolderLookup.Provider provider) {
        LumenRespawnData data = new LumenRespawnData();
        CompoundTag pending = tag.getCompound("Pending");
        for (String key : pending.getAllKeys()) {
            try {
                CompoundTag entry = pending.getCompound(key);
                data.entries.put(UUID.fromString(key),
                        new Entry(entry.getLong("Due"), entry.getString("Name")));
            } catch (IllegalArgumentException ignored) {
                // 坏 UUID 直接丢掉
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag pending = new CompoundTag();
        this.entries.forEach((owner, entry) -> {
            CompoundTag value = new CompoundTag();
            value.putLong("Due", entry.dueTick());
            value.putString("Name", entry.name());
            pending.put(owner.toString(), value);
        });
        tag.put("Pending", pending);
        return tag;
    }

    public Map<UUID, Entry> entries() {
        return this.entries;
    }

    public void add(UUID owner, long dueTick, String name) {
        this.entries.put(owner, new Entry(dueTick, name));
        setDirty();
    }

    public void remove(UUID owner) {
        this.entries.remove(owner);
        setDirty();
    }
}
