package dev.xingguang.terminalblade.entity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 随从「待复活」队列的持久化存储。
 *
 * <p>每只待复活随从按<b>自己的 UUID</b> 单独占一条记录，同一主人的多只随从不会互相覆盖；
 * 记录里保存死亡那一刻的完整实体 NBT（{@link net.minecraft.world.entity.Entity#saveWithoutId}），
 * 复活时原样还原——包括职责数据（驻守/床位/门绑定）、血量、名字与朝向。</p>
 *
 * <p>旧存档（按主人 UUID 存、只记名字和到期时间）在读取时自动迁移成新格式，
 * 快照为空，复活时退回基础属性。</p>
 */
public final class LumenRespawnData extends SavedData {
    private static final String FILE_ID = "mistletoe_respawns";

    /** 一条待复活记录。 */
    public static final class Entry {
        private final UUID owner;
        private final long dueTick;
        private final String name;
        private final CompoundTag snapshot;

        public Entry(UUID owner, long dueTick, String name, CompoundTag snapshot) {
            this.owner = owner;
            this.dueTick = dueTick;
            this.name = name;
            this.snapshot = snapshot;
        }

        public UUID owner() {
            return owner;
        }

        public long dueTick() {
            return dueTick;
        }

        public String name() {
            return name;
        }

        /** 死亡时的实体 NBT；旧档迁移过来的记录为 null。 */
        public CompoundTag snapshot() {
            return snapshot;
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public static LumenRespawnData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(LumenRespawnData::new, LumenRespawnData::load), FILE_ID);
    }

    public static LumenRespawnData load(CompoundTag tag, HolderLookup.Provider provider) {
        LumenRespawnData data = new LumenRespawnData();
        CompoundTag pending = tag.getCompound("Entries");
        for (String key : pending.getAllKeys()) {
            try {
                UUID petId = UUID.fromString(key);
                CompoundTag entry = pending.getCompound(key);
                data.entries.put(petId, new Entry(
                        UUID.fromString(entry.getString("Owner")),
                        entry.getLong("Due"),
                        entry.getString("Name"),
                        entry.contains("Snapshot", Tag.TAG_COMPOUND) ? entry.getCompound("Snapshot") : null));
            } catch (IllegalArgumentException ignored) {
                // 坏 UUID 直接丢掉
            }
        }
        // 旧格式：Pending 里按主人 UUID 存，只记 Due/Name
        CompoundTag legacy = tag.getCompound("Pending");
        for (String key : legacy.getAllKeys()) {
            try {
                UUID owner = UUID.fromString(key);
                CompoundTag entry = legacy.getCompound(key);
                data.entries.putIfAbsent(new UUID(0L, owner.hashCode() ^ 0x5EEDL),
                        new Entry(owner, entry.getLong("Due"), entry.getString("Name"), null));
            } catch (IllegalArgumentException ignored) {
                // 坏 UUID 直接丢掉
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag pending = new CompoundTag();
        this.entries.forEach((petId, entry) -> {
            CompoundTag value = new CompoundTag();
            value.putString("Owner", entry.owner().toString());
            value.putLong("Due", entry.dueTick());
            value.putString("Name", entry.name());
            if (entry.snapshot() != null) {
                value.put("Snapshot", entry.snapshot().copy());
            }
            pending.put(petId.toString(), value);
        });
        tag.put("Entries", pending);
        return tag;
    }

    public Map<UUID, Entry> entries() {
        return this.entries;
    }

    /** @param petId 随从自己的 UUID（不是主人的），这样同一主人的多只随从各占一条 */
    public void add(UUID petId, UUID owner, long dueTick, String name, CompoundTag snapshot) {
        this.entries.put(petId, new Entry(owner, dueTick, name, snapshot));
        setDirty();
    }

    public void remove(UUID petId) {
        this.entries.remove(petId);
        setDirty();
    }
}
