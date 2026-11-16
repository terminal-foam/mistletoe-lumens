package dev.xingguang.terminalblade.duty;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

final class ReliefLedger extends SavedData {
    static final Factory<ReliefLedger> FACTORY=new Factory<>(ReliefLedger::new,
        (tag,registries)->new ReliefLedger(tag.getCompound("Missions")),null);
    private final CompoundTag missions;
    private ReliefLedger(){this(new CompoundTag());}
    private ReliefLedger(CompoundTag missions){this.missions=missions;}
    static ReliefLedger get(ServerLevel level){
        return level.getDataStorage().computeIfAbsent(FACTORY,"mistletoe_lamp_relief");
    }
    CompoundTag mission(int raidId){return missions.getCompound(Integer.toString(raidId));}
    boolean sent(int raidId){return missions.contains(Integer.toString(raidId));}
    void arrived(int raidId,CompoundTag data){
        missions.put(Integer.toString(raidId),data);
        prune();
        setDirty();
    }
    /** 只保留最近 128 场袭击的记录，避免长期游玩后 SavedData 无限增长。 */
    private void prune(){
        if(missions.size()<=128)return;
        java.util.List<String> keys=new java.util.ArrayList<>(missions.getAllKeys());
        keys.sort(java.util.Comparator.comparingLong(k->{
            try{return Long.parseLong(k);}catch(NumberFormatException e){return 0L;}
        }));
        for(int i=0;i<keys.size()-128;i++)missions.remove(keys.get(i));
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registry){
        tag.put("Missions",missions.copy()); return tag;
    }
}
