package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.TerminalBlade;

import dev.xingguang.terminalblade.registry.ModSounds;

import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import dev.xingguang.terminalblade.entity.LumenSeishinEntity;
import dev.xingguang.terminalblade.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import dev.xingguang.terminalblade.registry.ModItems;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.IdentityHashMap;

@EventBusSubscriber(modid="mistletoe")
public final class RaidRelief {
    private static final String KEY="MistletoeReliefV1";
    private static final int RADIUS=24, MAX=96;
    private static final int BOSS=14;
    private static final List<DutyRules.Post> POSTS=DutyRules.reliefPosts();
    private static final Set<Raid> DISPATCHING=java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static final WeakHashMap<Mob,UnitState> UNITS=new WeakHashMap<>();
    private static final WeakHashMap<Mob,Long> PLUNGE=new WeakHashMap<>();
    private record RushState(Raider target,long until){}
    private static final WeakHashMap<Mob,RushState> RUSH=new WeakHashMap<>();

    // ── 护送返航（Unit 13）：Boss 瞬移的同一 tick 出发，沿地面连续迅冲回到 Boss 身边 ──
    /**
     * 一口气连冲的 tick 数。
     *
     * <p>按玩家的迅冲斩节奏取 1：玩家的续段 {@code rapid_slash_quick} 只占 1 帧（33 ms ≈ 1 tick），
     * 连点时就是"一刀接一刀"。这里同样每 tick 冲一段、每 tick 判一次扫掠。</p>
     */
    private static final int RUSH_BURST_TICKS=1;
    /** 两次连冲之间的空隙 tick（0 = 无缝连斩）。 */
    private static final int RUSH_GAP_TICKS=0;
    /** 单 tick 位移（格）。 */
    private static final double RUSH_STEP=1.4D;
    /** 扫掠判定宽度（格）。 */
    private static final double RUSH_WIDTH=1.4D;
    /** 抵达判定距离（格）。 */
    private static final double RUSH_REACH=3.0D;
    /** 返航最长时间（tick）。 */
    private static final int RUSH_MAX_TICKS=160;
    /** 沿途斩击的基础伤害（与初雪随从斩击一致）。 */
    private static final float RUSH_DAMAGE=2.0F;
    /** true = 每一段重置命中集；false = 整次返航每只敌人最多挨一次（默认）。 */
    private static final boolean HIT_PER_BURST=false;

    private static final java.util.Map<Mob,EscortReturn> RETURNS=new WeakHashMap<>();

    /** Unit 13 的返航状态。 */
    private static final class EscortReturn {
        final UUID boss;
        final long until;
        BlockPos target;
        long nextTarget;
        int burst;
        long nextBurst;
        final Set<UUID> hitTrip=new java.util.HashSet<>();
        Set<UUID> hitBurst=new java.util.HashSet<>();

        EscortReturn(UUID boss,long now){
            this.boss=boss;
            this.until=now+RUSH_MAX_TICKS;
        }
    }
    private static final java.util.Map<UUID,ReliefBar> BARS=new java.util.HashMap<>();
    private static final class ReliefBar {
        final ServerLevel level; final BlockPos center; final java.util.Set<UUID> units;
        final double totalMax; final ServerBossEvent bar;
        ReliefBar(ServerLevel level,BlockPos center,java.util.Set<UUID> units,double totalMax){
            this.level=level; this.center=center; this.units=units; this.totalMax=totalMax;
            this.bar=new ServerBossEvent(Component.translatable("bossbar.mistletoe.relief"),
                    BossEvent.BossBarColor.BLUE,BossEvent.BossBarOverlay.PROGRESS);
            this.bar.setDarkenScreen(false); this.bar.setPlayBossMusic(false); this.bar.setCreateWorldFog(false);
        }
    }
    private static final WeakHashMap<Raid,Layout> LAYOUTS=new WeakHashMap<>();
    private record UnitState(long nextRoute,long nextHit) {}
    private record Layout(long refresh,int wave,BlockPos spawn,BlockPos crowd,BlockPos intercept) {}

    private RaidRelief(){}
    public static boolean marked(Entity unit){return unit.getPersistentData().contains(KEY);}

    /** 只有本场袭击的成员可以选援军为目标；玩家/铁傀儡等一律不行。 */
    public static boolean canBeAttackedBy(LivingEntity attacker,LivingEntity target){
        if(!marked(target)||!(target.level() instanceof ServerLevel level))return true;
        Raid raid=mission(level,data(target));
        return raid!=null&&isMember(raid,attacker);
    }
    private static CompoundTag data(Entity e){return e.getPersistentData().getCompound(KEY);}
    private static boolean active(Raid raid){return raid!=null&&raid.isActive()&&!raid.isStopped()&&!raid.isOver();}
    public static boolean arrived(ServerLevel level,Raid raid){
        return active(raid)&&ReliefLedger.get(level).sent(raid.getId());
    }
    private static boolean isMember(Raid raid,LivingEntity target){
        return target instanceof Raider r && r.isAlive() && r.getCurrentRaid()==raid;
    }
    private static boolean nearHorizontal(Entity e,BlockPos pos,int radius){
        double dx=e.getX()-pos.getX()-.5,dz=e.getZ()-pos.getZ()-.5;
        return dx*dx+dz*dz<=(double)radius*radius;
    }
    private static boolean within(Entity e,BlockPos center,int range){
        return DutyRules.inArea(e.getX()-center.getX()-.5,e.getY()-center.getY(),e.getZ()-center.getZ()-.5,range);
    }
    private static boolean withinPos(BlockPos p,BlockPos center,int range){
        return DutyRules.inArea(p.getX()-center.getX(),p.getY()-center.getY(),p.getZ()-center.getZ(),range);
    }
    private static Raid mission(ServerLevel level,CompoundTag unit){
        Raid raid=level.getRaids().get(unit.getInt("Raid"));
        if(raid==null||raid.isStopped()||raid.isOver())return null;
        var record=ReliefLedger.get(level).mission(raid.getId());
        return unit.hasUUID("Mission")&&record.hasUUID("Mission")
            && unit.getUUID("Mission").equals(record.getUUID("Mission"))?raid:null;
    }
    public static boolean eligible(Mob mob,LivingEntity target){
        if(!(mob.level() instanceof ServerLevel level) || target==null || target.level()!=level) return false;
        var d=data(mob); Raid raid=mission(level,d);
        if(!active(raid)||!isMember(raid,target))return false;
        BlockPos center=BlockPos.of(d.getLong("Center"));
        int range=d.getInt("Unit")>=12?MAX:RADIUS;
        return within(mob,center,range+1)&&within(target,center,range);
    }

    public static void call(ServerPlayer player) {
        ServerLevel level=player.serverLevel();
        Raid raid=CompanionDuty.raid(level,player.blockPosition());
        if(raid==null||!nearHorizontal(player,raid.getCenter(),64))return; // A quiet chime and spark are the only effects outside a raid.
        int id=raid.getId();
        var ledger=ReliefLedger.get(level);
        if(ledger.sent(id)||!DISPATCHING.add(raid)){
            message(player,"already");return;
        }
        var units=new ArrayList<Mob>(15);
        var added=new ArrayList<Mob>(15);
        boolean committed=false;
        try {
            BlockPos center=raid.getCenter().immutable();
            List<BlockPos> entries=entryPositions(level,center,player.blockPosition());
            if(entries.size()<15){message(player,"no_space");return;}
            UUID marker=UUID.randomUUID();
            var boss=ModEntities.LUMEN_SEISHIN.get().create(level);
            if(boss==null){message(player,"no_space");return;}
            for(int i=0;i<14;i++){
                var unit=ModEntities.LUMEN_COMPANION.get().create(level);
                if(unit==null){message(player,"no_space");return;}
                unit.randomDisplayName();
                prepare(unit,i,entries.get(i),center,id,marker,boss.getUUID());
                units.add(unit);
            }
            prepare(boss,BOSS,entries.get(14),center,id,marker,boss.getUUID());
            units.add(boss);
            // Validate complete batch before moving any unit into the world.
            for(Mob unit:units)if(!level.noCollision(unit)){message(player,"no_space");return;}
            for(Mob unit:units){
                if(!level.addFreshEntity(unit)){message(player,"no_space");return;}
                added.add(unit);
            }
            CompoundTag record=new CompoundTag();
            record.putUUID("Mission",marker);
            record.putUUID("Commander",boss.getUUID());
            record.putLong("Center",center.asLong());
            ledger.arrived(id,record);
            committed=true;
            createBar(level,center,marker,added);
            var entry=entries.get(14);
            level.playSound(null,entry,ModSounds.LAMP_ANSWER.get(),SoundSource.PLAYERS,2.35f,1f);
            flash(level,entry.getX()+.5,entry.getY()+1.5,entry.getZ()+.5,36,1.4);
            message(player,"arrived");
        }finally{
            DISPATCHING.remove(raid);
            if(!committed)for(Mob unit:added)unit.discard();
        }
    }
    private static void prepare(Mob mob,int slot,BlockPos entry,BlockPos center,int raid,UUID mission,UUID commander){
        CompoundTag d=new CompoundTag();
        d.putInt("Unit",slot);d.putInt("Raid",raid);d.putUUID("Mission",mission);d.putUUID("Commander",commander);
        d.putLong("Center",center.asLong());d.putLong("Entry",entry.asLong());
        mob.getPersistentData().put(KEY,d);
        mob.moveTo(entry.getX()+.5,entry.getY(),entry.getZ()+.5,0,0);
        mob.setPersistenceRequired();
        // 援军移速：统一为 0.30（与原版基础一致，避免高速滑行/寻路打滑）
        var speed=mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if(speed!=null){
            speed.setBaseValue(0.30D);
        }
        if (mob.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
        if (mob instanceof LumenSeishinEntity boss) {
            boss.reliefPrepareFirstSnow();
        }
        initialize(mob);
    }
    private static void initialize(Mob mob){
        mob.removeFreeWill(); mob.setTarget(null); mob.getNavigation().stop();
        UNITS.put(mob,new UnitState(0,0));
    }
    private static void message(ServerPlayer p,String key){
        p.displayClientMessage(Component.translatable("message.mistletoe.lumen_lamp."+key),true);
    }
    static void flash(ServerLevel level,double x,double y,double z,int count,double spread){
        level.sendParticles(ParticleTypes.END_ROD,x,y,z,count,spread,spread*.55,spread,.022);
    }
    private static List<BlockPos> entryPositions(ServerLevel level,BlockPos center,BlockPos caller){
        var entry=new ArrayList<BlockPos>();
        int dx=caller.getX()-center.getX(), dz=caller.getZ()-center.getZ();
        if(dx==0&&dz==0)dz=1;
        int[] side=DutyRules.interceptOffset(dx*64,dz*64,RADIUS,0);
        // Prefer the edge facing the caller. Retry around the caller, then the center.
        for (BlockPos hint:List.of(center.offset(side[0],0,side[1]),caller,center)) {
            for(int ring=0;ring<=12;ring+=2) {
                for(int x=-ring;x<=ring;x+=2)for(int z=-ring;z<=ring;z+=2){
                    if(Math.max(Math.abs(x),Math.abs(z))!=ring) continue;
                    BlockPos p=ground(level,hint.offset(x,0,z),center,MAX);
                    if(p==null||!level.isPositionEntityTicking(p)||entry.stream().anyMatch(q->Math.abs(q.getX()-p.getX())<2&&Math.abs(q.getZ()-p.getZ())<2))continue;
                    var box=new AABB(p.getX()+.1,p.getY(),p.getZ()+.1,p.getX()+.9,p.getY()+2.1,p.getZ()+.9);
                    if(!level.getEntitiesOfClass(LivingEntity.class,box,LivingEntity::isAlive).isEmpty())continue;
                    entry.add(p);
                    if(entry.size()==15)return entry;
                }
            }
        }
        return entry;
    }
    private static BlockPos ground(ServerLevel level,BlockPos hint,BlockPos center,int radius){
        if(!level.hasChunkAt(hint))return null;
        int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,hint.getX(),hint.getZ());
        BlockPos surface=new BlockPos(hint.getX(),y,hint.getZ());
        // Check center-level ground first to favor roads over roofs. Then use surface as fallback.
        for(int dy:new int[]{0,1,-1,2,-2,3,-3,4,-4,5,-5,6,-6,7,-7,8,-8}){
            BlockPos p=new BlockPos(hint.getX(),center.getY()+dy,hint.getZ());
            if(withinPos(p,center,radius)&&safe(level,p))return p;
        }
        return withinPos(surface,center,radius)&&safe(level,surface)?surface:null;
    }
    private static boolean safe(ServerLevel level,BlockPos p){
        if(!level.isEmptyBlock(p)||!level.isEmptyBlock(p.above())||!level.isEmptyBlock(p.above().above()))return false;
        var floor=level.getBlockState(p.below());
        return floor.isFaceSturdy(level,p.below(),Direction.UP)&&!floor.is(Blocks.MAGMA_BLOCK)&&!floor.is(Blocks.CAMPFIRE)
            &&!floor.is(Blocks.SOUL_CAMPFIRE)&&!floor.is(Blocks.CACTUS)&&!floor.is(Blocks.POWDER_SNOW);
    }
    private static BlockPos post(ServerLevel level,BlockPos center,BlockPos hint,int radius,BlockPos backup){
        for(int ring=0;ring<=5;ring++)for(int dx=-ring;dx<=ring;dx++)for(int dz=-ring;dz<=ring;dz++){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=ring)continue;
            var p=ground(level,hint.offset(dx,0,dz),center,radius);
            if(p!=null)return p;
        }
        return backup;
    }
    private static Layout layout(ServerLevel level,Raid raid,BlockPos center){
        var previous=LAYOUTS.get(raid);
        var wave=WaveLog.last(raid);
        BlockPos recorded=wave==null||wave.number()!=raid.getGroupsSpawned()?null:wave.pos();
        BlockPos spawn=recorded;
        if(previous!=null && previous.refresh()>level.getGameTime()
           && previous.wave()==raid.getGroupsSpawned() && java.util.Objects.equals(previous.spawn(),spawn))return previous;
        if(spawn==null&&raid.getGroupsSpawned()>0) {
            spawn=raid.getAllRaiders().stream().filter(r->r.isAlive()&&r.getWave()==raid.getGroupsSpawned())
                    .min(Comparator.comparingDouble(r->r.distanceToSqr(center.getX()+.5,center.getY(),center.getZ()+.5)))
                    .map(Entity::blockPosition).orElse(null);
        }
        var box=new AABB(center.getX()-RADIUS,center.getY()-8,center.getZ()-RADIUS,center.getX()+RADIUS+1,center.getY()+9,center.getZ()+RADIUS+1);
        var residents=level.getEntitiesOfClass(Villager.class,box,v->v.isAlive()&&within(v,center,RADIUS));
        // Group by four-block cells and sum adjacent cells. Cost stays bounded even with many villagers.
        var cells=new HashMap<Long,Integer>();
        for(Villager v:residents){var vp=v.blockPosition();
            long key=BlockPos.asLong(Math.floorDiv(vp.getX(),4),Math.floorDiv(vp.getY(),4),Math.floorDiv(vp.getZ(),4));
            cells.merge(key,1,Integer::sum);
        }
        BlockPos prefer=previous!=null?previous.crowd():center;
        BlockPos crowd=prefer;
        int largest=-1; double best=Double.MAX_VALUE;
        for(Villager v:residents){
            var vp=v.blockPosition();int density=0, x=Math.floorDiv(vp.getX(),4),y=Math.floorDiv(vp.getY(),4),z=Math.floorDiv(vp.getZ(),4);
            for(int i=-1;i<=1;i++)for(int j=-1;j<=1;j++)for(int k=-1;k<=1;k++)density+=cells.getOrDefault(BlockPos.asLong(x+i,y+j,z+k),0);
            double dist=v.distanceToSqr(prefer.getX()+.5,prefer.getY(),prefer.getZ()+.5);
            if(density>largest||density==largest&&dist<best){largest=density;best=dist;crowd=vp;}
        }
        if(residents.isEmpty())crowd=center;
        BlockPos intercept=center;
        if(spawn!=null){
            int[] offset=DutyRules.interceptOffset(spawn.getX()-center.getX(),spawn.getZ()-center.getZ(),RADIUS,8);
            intercept=center.offset(offset[0],0,offset[1]);
        }
        var result=new Layout(level.getGameTime()+60,raid.getGroupsSpawned(),recorded,crowd,intercept);
        LAYOUTS.put(raid,result);return result;
    }

    public static boolean tickUnit(Mob mob){
        if(!marked(mob)||!(mob.level() instanceof ServerLevel level))return false;
        var d=data(mob); var raid=mission(level,d);
        if(raid==null){
            if(mob.isAlive()) { flash(level,mob.getX(),mob.getY()+.8,mob.getZ(),5,.2); mob.discard(); }
            return true;
        }
        if(!mob.isAlive())return true;
        if(!raid.isActive()){mob.setTarget(null);mob.getNavigation().stop();return true;}
        if(!UNITS.containsKey(mob))initialize(mob);
        var old=UNITS.get(mob);
        long now=level.getGameTime();
        // 护送返航（Unit 13）优先级最高：还在返航就完全接管本 tick
        var ret=RETURNS.get(mob);
        if(ret!=null&&tickEscortReturn(level,mob,ret,now))return true;
        var rush=RUSH.get(mob);
        if(rush!=null){
            if(!rush.target().isAlive()||now>rush.until()){
                RUSH.remove(mob);
            }else{
                dashRush(level,mob,rush.target(),now);
                return true;
            }
        }
        int slot=d.getInt("Unit");
        if(slot<0||slot>BOSS){mob.discard();return true;}
        var center=BlockPos.of(d.getLong("Center")); var entry=BlockPos.of(d.getLong("Entry"));
        Layout layout=layout(level,raid,center);
        BlockPos hint; int range, scan;
        switch(slot){
            case 0,1,2,3,4,5,6,7 -> {
                var role=POSTS.get(slot);
                int step=role.dx()!=0&&role.dz()!=0?14:20;
                hint=center.offset(role.dx()*step,0,role.dz()*step); range=RADIUS; scan=10;
            }
            case 8,9 -> {
                int n=(int)((now/120+(slot-8)*2)%4);
                hint=center.offset(new int[]{6,0,-6,0}[n],0,new int[]{0,6,0,-6}[n]); range=RADIUS;scan=8;
            }
            case 10,11 -> {
                int sign=slot==10?1:-1;
                hint=layout.crowd().offset(sign*3,0,sign*2);range=RADIUS;scan=8;
            }
            default -> {
                var boss=d.hasUUID("Commander")?level.getEntity(d.getUUID("Commander")):null;
                int sign=slot==12?1:-1;
                if(slot!=BOSS&&boss!=null&&boss.isAlive()&&within(boss,center,MAX)){
                    hint=boss.blockPosition().offset(sign*3,0,-sign*2);
                }else hint=layout.intercept().offset(slot==BOSS?0:sign*2,0,0);
                range=MAX;scan=12;
            }
        }
        if(!withinPos(hint,center,range)){
            int[] clamped=DutyRules.interceptOffset(hint.getX()-center.getX(),hint.getZ()-center.getZ(),range-1,0);
            hint=center.offset(clamped[0],0,clamped[1]);
        }
        Raider target=mob.getTarget() instanceof Raider r&&eligible(mob,r)?r:null;
        long nextRoute=old.nextRoute(), nextHit=old.nextHit();
        if(now>=nextRoute){
            nextRoute=now+14+(slot%5);
            BlockPos fixedHint=hint;
            var nearby=raid.getAllRaiders().stream()
                .filter(r->eligible(mob,r)&&CompanionDuty.near(r,fixedHint,scan))
                .min(Comparator.comparingDouble(r->mob.distanceToSqr(r)
                    -(slot==BOSS&&r instanceof net.minecraft.world.entity.monster.Ravager?64:0))).orElse(null);
            target=nearby;
            mob.setTarget(target);
            if(target!=null&&mob.distanceToSqr(target)>7&&within(mob,center,range))mob.getNavigation().moveTo(target,1.04);
            else if(target!=null)mob.getNavigation().stop();
            else {
                BlockPos backup=withinPos(entry,center,range)?entry:center;
                BlockPos p=post(level,center,hint,range,backup);
                if(!CompanionDuty.near(mob,p,1.5)){
                    boolean moved=mob.getNavigation().moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,1.0);
                    // 寻路失败（被门/栅栏/地形挡住）时，至少用移动控制朝岗位走，避免站桩。
                    if(!moved)mob.getMoveControl().setWantedPosition(p.getX()+.5,p.getY(),p.getZ()+.5,1.0);
                } else mob.getNavigation().stop();
            }
        }
        // 目标在跑/寻路失败时，直接用移动控制追，避免“站着看”。
        if(target!=null&&mob.distanceToSqr(target)>9&&mob.getNavigation().isDone()){
            mob.getMoveControl().setWantedPosition(target.getX(),target.getY(),target.getZ(),1.0);
        }
        if(now%10==0){
            openDoors(level,mob);
            climb(mob);
        }
        // 援军领队：初雪七连（原版 AI 已被 removeFreeWill，必须在这里推进）
        if(slot==BOSS&&mob instanceof LumenSeishinEntity b){
            b.reliefTickFrost();
            if(target!=null&&b.reliefFrostReady()&&mob.distanceToSqr(target)<=36.0D&&mob.hasLineOfSight(target)){
                b.reliefStartFrost(target);
            }
        }
        if(old.nextRoute()==0){
            TerminalBlade.LOGGER.info("[流明·星] 援军行动 slot={} 目标={} 中心={}",
                    slot,target==null?"无":target.getName().getString(),center);
        }
        if(target!=null&&now>=nextHit&&mob.hasLineOfSight(target)){
            double distance=mob.distanceToSqr(target);
            if(mob instanceof LumenSeishinEntity boss && distance<=25 && distance>10){
                boss.slashStrike(target);
                nextHit=now+70;
            } else if(distance<=10){
                boolean attack=mob.doHurtTarget(target);
                nextHit=now+(attack?(slot>=12?35:52):8);
            }
        }
        UNITS.put(mob,new UnitState(nextRoute,nextHit));
        return true;
    }
    /** 玩家副手举着流明灯、视野里出现袭击者时，触发流明空坠斩。 */
    @SubscribeEvent
    public static void lampWatch(PlayerTickEvent.Post event){
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        if(player.tickCount%5!=0)return;
        if(!player.getOffhandItem().is(ModItems.LUMEN_LAMP.get()))return;
        if(!(player.level() instanceof ServerLevel level))return;
        var raid=CompanionDuty.raid(level,player.blockPosition());
        if(raid==null||!raid.isActive()||raid.isOver())return;
        var look=player.getLookAngle();
        boolean spyglass=player.isUsingItem()&&player.getUseItem().is(net.minecraft.world.item.Items.SPYGLASS);
        double maxDist=spyglass?128.0D:48.0D;
        for(Raider raider:raid.getAllRaiders()){
            if(!raider.isAlive())continue;
            // 只看水平距离（x/z），望远镜看得更远
            double dx=raider.getX()-player.getX(),dz=raider.getZ()-player.getZ();
            if(dx*dx+dz*dz>maxDist*maxDist)continue;
            if(!player.hasLineOfSight(raider))continue;
            var to=raider.position().add(0,1,0).subtract(player.getEyePosition()).normalize();
            if(look.dot(to)<0.5)continue; // 约 60° 视野锥
            trySkyPlunge(player,raider);
            return;
        }
    }

    private static void escortFollowUp(LumenSeishinEntity boss,Raider primary){
        if(!(boss.level() instanceof ServerLevel level))return;
        Raid raid=mission(level,data(boss));
        if(raid==null||!raid.isActive())return;
        LumenCompanionEntity a=null;
        for(var c:level.getEntitiesOfClass(LumenCompanionEntity.class,new AABB(boss.blockPosition()).inflate(160))){
            if(!marked(c))continue;
            if(data(c).getInt("Unit")==12){a=c;break;}
        }
        Raider far=raid.getAllRaiders().stream()
                .filter(Raider::isAlive)
                .max(Comparator.comparingDouble(r->r.distanceToSqr(boss)))
                .orElse(primary);
        // Unit 13 的迅冲返航已经在 Boss 瞬移那一刻由 startEscortReturn 启动，这里只管 Unit 12 的空降
        if(a!=null)companionSkyPlunge(a,far);
    }

    private static void companionSkyPlunge(LumenCompanionEntity escort,Raider target){
        if(!(escort.level() instanceof ServerLevel level))return;
        double x=target.getX(),z=target.getZ();
        int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)Math.floor(x),(int)Math.floor(z));
        double y=Math.max(target.getY()+8.0D,top+6.0D);
        escort.teleportTo(x,y,z);
        escort.setDeltaMovement(0,-1.4D,0);
        escort.setTarget(target);
        escort.getNavigation().stop();
        level.getServer().tell(new net.minecraft.server.TickTask(18,()->{
            if(!escort.isAlive()||!target.isAlive())return;
            escort.slashStrike(target);
            level.playSound(null,target.blockPosition(),SoundEvents.PLAYER_ATTACK_CRIT,SoundSource.PLAYERS,1.0f,0.9f);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK,
                    target.getX(),target.getY()+1.0D,target.getZ(),8,0.3D,0.3D,0.3D,0.0D);
        }));
    }

    /** 连续迅冲：沿目标方向快速位移并周期性挥砍。 */
    private static void dashRush(ServerLevel level,Mob mob,Raider target,long now){        mob.setTarget(target);
        mob.getLookControl().setLookAt(target,180.0f,180.0f);
        double dx=target.getX()-mob.getX(),dz=target.getZ()-mob.getZ();
        double d=Math.sqrt(dx*dx+dz*dz);
        if(d>2.4){
            double step=Math.min(1.1D,d-2.0D);
            double nx=mob.getX()+dx/d*step, nz=mob.getZ()+dz/d*step;
            // 贴着地面冲：平地/缓坡直接跟随地面高度；陡坡或悬崖停止迅冲，交给正常 AI。
            int gy=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int)Math.floor(nx),(int)Math.floor(nz));
            double dy=gy-mob.getY();
            if(dy>1.0D||dy<-3.0D){
                RUSH.remove(mob);
                mob.getNavigation().stop();
                return;
            }
            var box=mob.getBoundingBox().move(nx-mob.getX(),gy-mob.getY(),nz-mob.getZ());
            if(!level.noCollision(mob,box)){
                RUSH.remove(mob);
                mob.getNavigation().stop();
                return;
            }
            mob.teleportTo(nx,gy,nz);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK,
                    mob.getX(),mob.getY()+1.0D,mob.getZ(),3,0.2D,0.2D,0.2D,0.0D);
        }
        if(d<=3.2D&&now%8==0)mob.doHurtTarget(target);
    }

    /**
     * 启动 Unit 13 的迅冲返航：在 Boss {@code teleportTo} 落点的**同一 tick** 调用。
     * 只找 13 号位；找不到就什么都不做（袭击还没刷出护送单位时会这样）。
     */
    private static void startEscortReturn(LumenSeishinEntity boss){
        if(!(boss.level() instanceof ServerLevel level))return;
        if(mission(level,data(boss))==null)return;
        for(var c:level.getEntitiesOfClass(LumenCompanionEntity.class,new AABB(boss.blockPosition()).inflate(160))){
            if(!marked(c)||data(c).getInt("Unit")!=13)continue;
            RETURNS.put(c,new EscortReturn(boss.getUUID(),level.getGameTime()));
            c.holdAction(dev.xingguang.terminalblade.entity.LumenAction.RUSH);
            TerminalBlade.LOGGER.info("[流明·星] 迅冲返航出发：{} 距 Boss {} 格",
                    c.getName().getString(),String.format("%.1f",Math.sqrt(c.distanceToSqr(boss))));
            return;
        }
    }

    /**
     * 推进返航。
     *
     * @return {@code true} = 本 tick 仍由返航接管；{@code false} = 已收刀，交还常规护卫逻辑
     */
    private static boolean tickEscortReturn(ServerLevel level,Mob mob,EscortReturn state,long now){
        var boss=level.getEntity(state.boss) instanceof LumenSeishinEntity b?b:null;
        var raid=mission(level,data(mob));
        // 状态优先级：Boss 还在 → 袭击还开 → 未超时 → 未抵达
        if(boss==null||!boss.isAlive()||raid==null||!raid.isActive()){
            endEscortReturn(mob,"Boss 或袭击结束");
            return false;
        }
        if(now>state.until){
            endEscortReturn(mob,"超时");
            return false;
        }
        // 抵达判定用**水平距离**：Boss 瞬移后在天上，3D 距离会被高度差撑大
        BlockPos arrive=state.target!=null?state.target:boss.blockPosition();
        double hx=mob.getX()-(arrive.getX()+0.5D),hz=mob.getZ()-(arrive.getZ()+0.5D);
        if(Math.sqrt(hx*hx+hz*hz)<=RUSH_REACH){
            endEscortReturn(mob,"已归队");
            return false;
        }
        // 目标点每 10 tick 重取：Boss 再瞬移就自动跟新坐标
        if(state.target==null||now>=state.nextTarget){
            state.target=escortTarget(level,boss);
            state.nextTarget=now+10L;
        }
        // 连冲节奏：冲 RUSH_BURST_TICKS 次、歇 RUSH_GAP_TICKS 次
        if(state.burst<=0){
            if(now<state.nextBurst){
                stepToward(level,mob,state.target,0.35D);
                return true;
            }
            state.burst=RUSH_BURST_TICKS;
            state.nextBurst=now+RUSH_GAP_TICKS;
            if(HIT_PER_BURST)state.hitBurst=new java.util.HashSet<>();
        }
        state.burst--;
        dashSegment(level,mob,state.target,state);
        return true;
    }

    /** 收刀：解除持续动作、清状态，交还常规 AI。 */
    private static void endEscortReturn(Mob mob,String reason){
        RETURNS.remove(mob);
        TerminalBlade.LOGGER.info("[流明·星] 迅冲返航结束（{}）：{}",reason,mob.getName().getString());
        if(mob instanceof LumenCompanionEntity c)c.clearHoldAction();
        mob.getNavigation().stop();
    }

    /** 返航目标：Boss 水平位置附近的安全地面格（Boss 在空中时取它正下方的地面）。 */
    private static BlockPos escortTarget(ServerLevel level,LumenSeishinEntity boss){
        BlockPos base=boss.blockPosition();
        BlockPos safe=VillageRespawns.findSafe(level,base,3);
        if(safe!=null)return safe;
        int gy=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,base.getX(),base.getZ());
        return new BlockPos(base.getX(),gy,base.getZ());
    }

    /** 朝目标冲刺一步；落差/碰撞不允许就交还寻路，下一 tick 再冲。 */
    private static void dashSegment(ServerLevel level,Mob mob,BlockPos target,EscortReturn state){
        if(!(mob instanceof LumenCompanionEntity escort))return;
        double dx=target.getX()+0.5D-mob.getX(),dz=target.getZ()+0.5D-mob.getZ();
        double d=Math.sqrt(dx*dx+dz*dz);
        if(d<1.0D){
            stepToward(level,mob,target,0.35D);
            return;
        }
        double step=Math.min(RUSH_STEP,d);
        double nx=mob.getX()+dx/d*step,nz=mob.getZ()+dz/d*step;
        int gy=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int)Math.floor(nx),(int)Math.floor(nz));
        double dy=gy-mob.getY();
        if(dy>1.2D||dy<-3.0D){
            stall(level,mob,target,state);
            return;
        }
        if(!level.noCollision(mob,mob.getBoundingBox().move(nx-mob.getX(),dy,nz-mob.getZ()))){
            stall(level,mob,target,state);
            return;
        }
        double ax=mob.getX(),ay=mob.getY(),az=mob.getZ();
        mob.teleportTo(nx,gy,nz);
        mob.getLookControl().setLookAt(nx,gy+1.0D,nz,180.0f,180.0f);
        sweepCut(level,escort,ax,ay+0.9D,az,nx,gy+0.9D,nz,state);
    }

    /** 被地形挡住：中断当前连冲、改用寻路绕一段，同时保持动势。 */
    private static void stall(ServerLevel level,Mob mob,BlockPos target,EscortReturn state){
        state.burst=0;
        state.nextBurst=level.getGameTime()+2L;
        stepToward(level,mob,target,0.5D);
    }

    /** 小步挪动（不传送）：连冲空隙与被挡住时保持动势；跨不了台阶就交给寻路。 */
    private static void stepToward(ServerLevel level,Mob mob,BlockPos target,double speed){
        double dx=target.getX()+0.5D-mob.getX(),dz=target.getZ()+0.5D-mob.getZ();
        double d=Math.sqrt(dx*dx+dz*dz);
        if(d<0.6D){
            mob.getNavigation().stop();
            return;
        }
        double step=Math.min(speed,d);
        double nx=mob.getX()+dx/d*step,nz=mob.getZ()+dz/d*step;
        int gy=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int)Math.floor(nx),(int)Math.floor(nz));
        if(Math.abs(gy-mob.getY())>1.2D
                ||!level.noCollision(mob,mob.getBoundingBox().move(nx-mob.getX(),gy-mob.getY(),nz-mob.getZ()))){
            mob.getNavigation().moveTo(target.getX()+0.5D,target.getY(),target.getZ()+0.5D,1.2D);
            return;
        }
        mob.teleportTo(nx,gy,nz);
    }

    /**
     * 沿 A→B 的扫掠区域斩击沿途袭击者，并撒出连成一条线的冷光拖尾。
     *
     * <p>逐段判定而不是"贴近了才砍"，避免 1.4 格/tick 高速掠过时漏伤。</p>
     */
    private static void sweepCut(ServerLevel level,LumenCompanionEntity mob,
                                 double ax,double ay,double az,double bx,double by,double bz,
                                 EscortReturn state){
        double half=RUSH_WIDTH*0.5D;
        var box=new AABB(ax,ay,az,bx,by,bz).inflate(half,1.1D,half);
        for(var r:level.getEntitiesOfClass(Raider.class,box,Raider::isAlive)){
            var id=r.getUUID();
            if(state.hitTrip.contains(id)||state.hitBurst.contains(id))continue;
            if(!CompanionDuty.frostDamage(mob,r,dev.xingguang.terminalblade.combat.MistletoeDamage.LUMEN_BLADE,RUSH_DAMAGE))continue;
            state.hitTrip.add(id);
            state.hitBurst.add(id);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK,r.getX(),r.getY()+1.0D,r.getZ(),6,0.3D,0.3D,0.3D,0.0D);
        }
        double len=Math.sqrt((bx-ax)*(bx-ax)+(bz-az)*(bz-az));
        int steps=Math.max(1,(int)Math.ceil(len/0.6D));
        for(int i=0;i<=steps;i++){
            double t=(double)i/steps;
            level.sendParticles(ParticleTypes.END_ROD,
                    ax+(bx-ax)*t,ay+(by-ay)*t,az+(bz-az)*t,1,0.02D,0.02D,0.02D,0.0D);
        }
    }

    private static void trySkyPlunge(ServerPlayer player,Raider raider){
        if(!(player.level() instanceof ServerLevel level))return;
        long now=level.getGameTime();
        for(var boss:level.getEntitiesOfClass(LumenSeishinEntity.class,new AABB(player.blockPosition()).inflate(256))){
            if(!marked(boss)||data(boss).getInt("Unit")!=BOSS)continue;
            if(mission(level,data(boss))==null)continue;
            boolean raidersNear=!level.getEntitiesOfClass(Raider.class,new AABB(boss.blockPosition()).inflate(8),Raider::isAlive).isEmpty();
            if(raidersNear)continue;
            Long last=PLUNGE.get(boss);
            if(last!=null&&now-last<120)continue;
            PLUNGE.put(boss,now);
            skyPlunge(boss,player,raider);
            return;
        }
    }

    private static void openDoors(ServerLevel level,Mob mob){
        BlockPos base=mob.blockPosition();
        for(BlockPos p:BlockPos.betweenClosed(base.offset(-2,-1,-2),base.offset(2,2,2))){
            BlockState s=level.getBlockState(p);
            if(s.getBlock() instanceof DoorBlock door&&!s.getValue(DoorBlock.OPEN)&&DoorBlock.isWoodenDoor(s)){
                door.setOpen(mob,level,s,p,true);
                BlockPos at=p.immutable();
                level.getServer().tell(new net.minecraft.server.TickTask(20,()->{
                    BlockState cur=level.getBlockState(at);
                    if(cur.getBlock() instanceof DoorBlock d&&cur.getValue(DoorBlock.OPEN))d.setOpen(null,level,cur,at,false);
                }));
            }
        }
    }
    private static void climb(Mob mob){
        if(mob.onClimbable()){
            var m=mob.getDeltaMovement();
            mob.setDeltaMovement(m.x,0.2D,m.z);
        }
    }

    private static void skyPlunge(LumenSeishinEntity boss,ServerPlayer player,Raider target){
        var level=(ServerLevel)boss.level();
        // 瞬移到袭击者上方，而不是玩家上方
        double x=target.getX(),z=target.getZ();
        int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)Math.floor(x),(int)Math.floor(z));
        double y=Math.max(target.getY()+8.0D,top+6.0D);
        boss.teleportTo(x,y,z);
        boss.setDeltaMovement(0,-1.4D,0);
        boss.setTarget(target);
        boss.getNavigation().stop();
        // ★ Unit 13 在瞬移落点的同一 tick 出发，不等下面 18 tick 的落地斩击
        startEscortReturn(boss);
        level.playSound(null,target.blockPosition(),SoundEvents.ENDERMAN_TELEPORT,SoundSource.HOSTILE,1.0f,0.8f);
        level.getServer().tell(new net.minecraft.server.TickTask(18,()->{
            if(!boss.isAlive()||!target.isAlive())return;
            boss.slashStrike(target);
            level.playSound(null,target.blockPosition(),SoundEvents.PLAYER_ATTACK_CRIT,SoundSource.PLAYERS,1.2f,0.8f);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK,
                    target.getX(),target.getY()+1.0D,target.getZ(),8,0.3D,0.3D,0.3D,0.0D);
            // 流明落地后：一名护送对最远袭击者空坠，另一名连续迅冲赶路
            escortFollowUp(boss,target);
        }));
    }

    private static void createBar(ServerLevel level,BlockPos center,UUID mission,java.util.List<Mob> units){
        double total=0;
        var ids=new java.util.HashSet<UUID>();
        for(Mob m:units){ if(m!=null){ ids.add(m.getUUID()); total+=m.getMaxHealth(); } }
        var relief=new ReliefBar(level,center,ids,total);
        BARS.put(mission,relief);
        relief.bar.setVisible(true);
        relief.bar.setProgress(1.0f);
        for(ServerPlayer p:level.players()){ if(p.distanceToSqr(center.getX()+0.5,center.getY(),center.getZ()+0.5)<=96*96) relief.bar.addPlayer(p); }
    }

    @SubscribeEvent
    public static void tickBars(LevelTickEvent.Post event){
        if(!(event.getLevel() instanceof ServerLevel level))return;
        if(level.getGameTime()%10!=0)return;
        var it=BARS.entrySet().iterator();
        while(it.hasNext()){
            var entry=it.next(); var bar=entry.getValue();
            // 只在血条所属维度更新；否则其它维度的 tick 会把它当成“全员死亡”删掉。
            if(bar.level!=level)continue;
            double cur=0; int alive=0;
            for(UUID id:bar.units){
                var e=level.getEntity(id);
                if(e instanceof LivingEntity living&&living.isAlive()){ cur+=living.getHealth(); alive++; }
            }
            if(alive==0||bar.totalMax<=0){ bar.bar.setVisible(false); bar.bar.removeAllPlayers(); it.remove(); continue; }
            bar.bar.setProgress((float)Math.max(0,Math.min(1,cur/bar.totalMax)));
            for(ServerPlayer p:java.util.List.copyOf(level.players())){
                boolean in=p.distanceToSqr(bar.center.getX()+0.5,bar.center.getY(),bar.center.getZ()+0.5)<=96*96;
                if(in)bar.bar.addPlayer(p); else bar.bar.removePlayer(p);
            }
        }
    }

    @SubscribeEvent
    public static void filterDamage(LivingIncomingDamageEvent event){
        Entity attacker=event.getSource().getEntity();
        var victim=event.getEntity();
        if(attacker instanceof Mob mob&&marked(mob)&&!eligible(mob,victim)){event.setCanceled(true);return;}
        if(marked(victim)&&attacker!=null&&victim.level() instanceof ServerLevel level){
            var raid=mission(level,data(victim));
            if(raid==null||!(attacker instanceof LivingEntity enemy)||!isMember(raid,enemy))event.setCanceled(true);
        }
    }
    @SubscribeEvent
    public static void noDrops(LivingDropsEvent e){if(marked(e.getEntity()))e.getDrops().clear();}
    @SubscribeEvent
    public static void noXp(LivingExperienceDropEvent e){if(marked(e.getEntity()))e.setDroppedExperience(0);}
    @SubscribeEvent
    public static void noRecruit(PlayerInteractEvent.EntityInteract e){
        if(marked(e.getTarget())){
            e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);e.setCanceled(true);
        }
    }
}
