package dev.xingguang.terminalblade.duty;

import java.util.UUID;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import dev.xingguang.terminalblade.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid="mistletoe")
public final class VillageRespawns extends SavedData {
    private static final SavedData.Factory<VillageRespawns> FACTORY =
        new SavedData.Factory<>(VillageRespawns::new,(nbt,registries)->new VillageRespawns(nbt.getCompound("Entries")),null);
    private final CompoundTag entries;
    public VillageRespawns(){ this(new CompoundTag()); }
    private VillageRespawns(CompoundTag nbt){ entries=nbt; }
    static VillageRespawns of(ServerLevel level){
        return level.getDataStorage().computeIfAbsent(FACTORY,"mistletoe_village_respawns");
    }
    @Override public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.put("Entries",entries);return nbt;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof LumenCompanionEntity mob)
                || !(mob.level() instanceof ServerLevel level) || !CompanionDuty.village(mob)
                || CompanionDuty.relief(mob)) return;
        var d=CompanionDuty.data(mob); var center=CompanionDuty.pos(d,"Anchor",level);
        if (center==null) return;
        var queue=of(level); var e=new CompoundTag();
        e.put("Duty",d.copy()); e.putLong("Due",level.getGameTime()+200);
        e.putString("Name",mob.getName().getString());
        // Same UUID prevents a second live copy after a partially saved restart.
        queue.entries.put(mob.getUUID().toString(),e);queue.setDirty();
    }
    @SubscribeEvent
    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime()%20!=0) return;
        var queue=of(level);
        for(String key : java.util.List.copyOf(queue.entries.getAllKeys())) {
            if (!queue.entries.contains(key,Tag.TAG_COMPOUND)) continue;
            var e=queue.entries.getCompound(key);
            if(level.getGameTime()<e.getLong("Due"))continue;
            var duty=e.getCompound("Duty"); var center=CompanionDuty.pos(duty,"Anchor",level);
            if(center==null || !level.hasChunkAt(center) || CompanionDuty.raid(level,center)!=null)continue;
            UUID id=UUID.fromString(key);
            var existing=level.getEntity(id);
            if(existing!=null) {
                if(existing.isAlive()) {queue.entries.remove(key);queue.setDirty();}
                continue;
            }
            BlockPos bed=CompanionDuty.bedHead(level,CompanionDuty.pos(duty,"Bed",level));
            var spawn=findSafe(level,bed,2);
            if(spawn==null)spawn=findSafe(level,center,4);
            if(spawn==null)continue;
            var mob=ModEntities.LUMEN_COMPANION.get().create(level);
            if(mob==null)continue;
            mob.setUUID(id);
            mob.setCustomName(Component.literal(e.getString("Name")));
            CompanionDuty.save(mob,duty.copy());
            mob.moveTo(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5,0,0);
            if(!level.noCollision(mob) || !level.addFreshEntity(mob))continue;
            queue.entries.remove(key);queue.setDirty();
        }
    }
    static BlockPos findSafe(ServerLevel level,BlockPos anchor,int radius){
        if(anchor==null)return null;
        for(int r=0;r<=radius;r++)for(int dy:new int[]{0,1,-1,2,-2})for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
            var p=anchor.offset(dx,dy,dz);
            if(level.hasChunkAt(p) && level.isEmptyBlock(p) && level.isEmptyBlock(p.above())
                    && level.getBlockState(p.below()).isFaceSturdy(level,p.below(),net.minecraft.core.Direction.UP)
                    && !(level.getBlockState(p.below()).getBlock() instanceof net.minecraft.world.level.block.MagmaBlock))return p;
        }
        return null;
    }
}
