package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import dev.xingguang.terminalblade.registry.ModEntities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 流明灯里的「随从收纳」。
 *
 * <p>平时右键流明灯：把所有处于<b>跟随状态</b>（已驯服、未坐下、不是袭击援军）的随从序列化收进灯里；
 * 再次右键全部放出来。袭击中右键则是「求援 + 放出收纳的随从」同时生效，蹲下右键仍可手动收/放。</p>
 *
 * <p>收纳的实体快照存在世界存档 {@code mistletoe_companion_storage.dat}，关档/重启不会丢；
 * 放出时还原职责数据（驻守/床位/门绑定），血量按上限修复，避免带着死亡快照放出来即死。</p>
 */
public final class CompanionStorage extends SavedData {
    private static final String FILE_ID = "mistletoe_companion_storage";

    private final Map<UUID, List<CompoundTag>> stored = new HashMap<>();

    private CompanionStorage() {
    }

    public static CompanionStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CompanionStorage::new, CompanionStorage::load), FILE_ID);
    }

    private static CompanionStorage load(CompoundTag tag, HolderLookup.Provider provider) {
        CompanionStorage data = new CompanionStorage();
        CompoundTag players = tag.getCompound("Players");
        for (String key : players.getAllKeys()) {
            try {
                UUID owner = UUID.fromString(key);
                ListTag pets = players.getList(key, Tag.TAG_COMPOUND);
                List<CompoundTag> list = new ArrayList<>();
                for (int i = 0; i < pets.size(); i++) {
                    list.add(pets.getCompound(i).copy());
                }
                if (!list.isEmpty()) {
                    data.stored.put(owner, list);
                }
            } catch (RuntimeException ignored) {
                // 坏记录直接丢弃
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag players = new CompoundTag();
        this.stored.forEach((owner, list) -> {
            ListTag pets = new ListTag();
            for (CompoundTag snapshot : list) {
                pets.add(snapshot.copy());
            }
            players.put(owner.toString(), pets);
        });
        tag.put("Players", players);
        return tag;
    }

    public int storedCount(UUID owner) {
        List<CompoundTag> list = this.stored.get(owner);
        return list == null ? 0 : list.size();
    }

    /** 玩家灯里现在收纳了几只随从（没有存档时返回 0）。 */
    public static int storedCountOf(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server == null ? 0 : get(server).storedCount(player.getUUID());
    }

    /**
     * 收起所有处于跟随状态的随从。
     *
     * <p>只收登记表里属于该玩家、且没有坐下的随从；如果它所在的区块没加载，会先把区块从磁盘读回来
     * （随从待过的区块一定生成过，不会在这里生成新地形）。</p>
     *
     * @return 收起的数量
     */
    public static int stow(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }
        CompanionTracker tracker = CompanionTracker.get(server);
        CompanionStorage store = get(server);
        int moved = 0;
        for (Map.Entry<UUID, CompanionTracker.Entry> entry
                : new ArrayList<>(tracker.entries().entrySet())) {
            UUID id = entry.getKey();
            CompanionTracker.Entry info = entry.getValue();
            if (!info.owner().equals(player.getUUID()) || info.sitting()) {
                continue;
            }
            ServerLevel level = server.getLevel(info.dimension());
            if (level == null) {
                tracker.remove(id);
                continue;
            }
            Entity entity = level.getEntities().get(id);
            if (entity == null) {
                level.getChunk(info.pos().getX() >> 4, info.pos().getZ() >> 4);
                entity = level.getEntities().get(id);
            }
            if (!(entity instanceof LumenCompanionEntity pet) || !pet.isAlive()) {
                tracker.remove(id);
                continue;
            }
            if (pet.isOrderedToSit() || pet.isVehicle() || pet.isPassenger() || RaidRelief.marked(pet)) {
                continue;
            }
            CompoundTag snapshot = pet.saveWithoutId(new CompoundTag());
            store.listFor(player.getUUID()).add(snapshot);
            pet.discard();
            tracker.remove(id);
            moved++;
        }
        if (moved > 0) {
            store.setDirty();
            TerminalBlade.LOGGER.info("[流明·星] {} 收起了 {} 只随从", player.getName().getString(), moved);
        }
        return moved;
    }

    /**
     * 放出灯里收纳的随从（在主人附近的安全落点重新生成）。
     *
     * @return 放出的数量；主人身边没有安全落点时剩余记录会保留，等下次再放
     */
    public static int release(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }
        CompanionStorage store = get(server);
        List<CompoundTag> list = store.stored.get(player.getUUID());
        if (list == null || list.isEmpty()) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        int released = 0;
        Iterator<CompoundTag> it = list.iterator();
        while (it.hasNext()) {
            CompoundTag snapshot = it.next();
            BlockPos spot = SafeSpots.find(level, player.blockPosition());
            if (spot == null) {
                break;
            }
            LumenCompanionEntity pet = ModEntities.LUMEN_COMPANION.get().create(level);
            if (pet == null) {
                break;
            }
            try {
                pet.load(snapshot);
            } catch (RuntimeException e) {
                TerminalBlade.LOGGER.warn("[流明·星] 灯里的随从快照读取失败，跳过一只", e);
                it.remove();
                store.setDirty();
                continue;
            }
            pet.setHealth(pet.getMaxHealth());
            pet.setDeltaMovement(0.0D, 0.0D, 0.0D);
            pet.setRemainingFireTicks(0);
            pet.fallDistance = 0.0F;
            pet.hurtTime = 0;
            pet.deathTime = 0;
            pet.invulnerableTime = 0;
            pet.moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, player.getYRot(), 0.0F);
            pet.setTame(true, true);
            pet.setOwnerUUID(player.getUUID());
            if (!level.addFreshEntity(pet)) {
                break;
            }
            it.remove();
            released++;
        }
        if (released > 0) {
            store.setDirty();
            TerminalBlade.LOGGER.info("[流明·星] {} 放出了 {} 只随从", player.getName().getString(), released);
        }
        return released;
    }

    private List<CompoundTag> listFor(UUID owner) {
        return this.stored.computeIfAbsent(owner, k -> new ArrayList<>());
    }
}
