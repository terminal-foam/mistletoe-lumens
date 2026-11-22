package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 随从位置登记表（世界存档）。
 *
 * <p>随从所在区块一旦卸载，服务端内存里就没有这个实体了，只剩磁盘上的区块 NBT。
 * 这份登记表按随从 UUID 记住它「最后在哪个维度、哪个位置」，需要时由
 * {@link CompanionTeleport} 把区块读回来，再把随从送到主人身边。</p>
 *
 * <p>登记表在随从自己的 tick 里刷新（每 20 tick 一次，开销可以忽略）；随从死亡时移除，
 * 复活出来的新实体会用新 UUID 重新登记。</p>
 */
public final class CompanionTracker extends SavedData {
    private static final String FILE_ID = "mistletoe_companions";

    /** 一只随从的最后已知状态。 */
    public record Entry(UUID owner, ResourceKey<Level> dimension, BlockPos pos, boolean sitting, long seenAt) {
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    private CompanionTracker() {
    }

    public static CompanionTracker get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CompanionTracker::new, CompanionTracker::load), FILE_ID);
    }

    private static CompanionTracker load(CompoundTag tag, HolderLookup.Provider provider) {
        CompanionTracker data = new CompanionTracker();
        CompoundTag all = tag.getCompound("Companions");
        for (String key : all.getAllKeys()) {
            try {
                CompoundTag value = all.getCompound(key);
                ResourceLocation dimension = ResourceLocation.tryParse(value.getString("Dim"));
                if (dimension == null) {
                    continue;
                }
                data.entries.put(UUID.fromString(key), new Entry(
                        UUID.fromString(value.getString("Owner")),
                        ResourceKey.create(Registries.DIMENSION, dimension),
                        BlockPos.of(value.getLong("Pos")),
                        value.getBoolean("Sitting"),
                        value.getLong("Seen")));
            } catch (RuntimeException ignored) {
                // 坏记录直接丢弃，避免一条脏数据卡住整个登记表
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag all = new CompoundTag();
        this.entries.forEach((id, entry) -> {
            CompoundTag value = new CompoundTag();
            value.putString("Owner", entry.owner().toString());
            value.putString("Dim", entry.dimension().location().toString());
            value.putLong("Pos", entry.pos().asLong());
            value.putBoolean("Sitting", entry.sitting());
            value.putLong("Seen", entry.seenAt());
            all.put(id.toString(), value);
        });
        tag.put("Companions", all);
        return tag;
    }

    public Map<UUID, Entry> entries() {
        return this.entries;
    }

    /** 随从 tick 时调用：刷新自己的位置。未驯服/无主（例如临时援军）不登记。 */
    public static void touch(ServerLevel level, LumenCompanionEntity pet) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        UUID owner = pet.getOwnerUUID();
        if (!pet.isTame() || owner == null) {
            get(server).remove(pet.getUUID());
            return;
        }
        get(server).put(pet.getUUID(), owner, level.dimension(), pet.blockPosition(),
                pet.isOrderedToSit(), level.getGameTime());
    }

    public void put(UUID id, UUID owner, ResourceKey<Level> dimension, BlockPos pos, boolean sitting, long seenAt) {
        this.entries.put(id, new Entry(owner, dimension, pos, sitting, seenAt));
        setDirty();
    }

    public void remove(UUID id) {
        if (this.entries.remove(id) != null) {
            setDirty();
        }
    }
}
