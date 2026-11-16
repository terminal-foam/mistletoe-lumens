package dev.xingguang.terminalblade.duty;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import dev.xingguang.terminalblade.combat.MistletoeDamage;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import dev.xingguang.terminalblade.entity.LumenSeishinEntity;
import java.util.EnumSet;
import java.util.UUID;

@EventBusSubscriber(modid="mistletoe")
public final class CompanionDuty {
    static final String KEY = "MistletoeDutyV1";
    private static final String SELECT = "MistletoeDutySelectionV1";

    static CompoundTag data(LumenCompanionEntity mob) { return mob.getPersistentData().getCompound(KEY); }
    static void save(LumenCompanionEntity mob, CompoundTag data) { mob.getPersistentData().put(KEY, data); }
    static String dimension(ServerLevel level) { return level.dimension().location().toString(); }
    static boolean village(LumenCompanionEntity mob) { return !mob.isTame() && data(mob).getBoolean("Village"); }
    /** 临时援军：玩家无法伤害、无法驯服，袭击结束即撤离。 */
    public static boolean relief(LumenCompanionEntity mob) {
        return data(mob).getBoolean("Relief") || RaidRelief.marked(mob);
    }
    static boolean guard(LumenCompanionEntity mob) {
        return data(mob).getBoolean("Guard") && (mob.isTame() || village(mob) || data(mob).hasUUID("Leader"));
    }
    static BlockPos pos(CompoundTag d,String key, ServerLevel level) {
        return d.contains(key) && dimension(level).equals(d.getString(key+"Dim")) ? BlockPos.of(d.getLong(key)) : null;
    }
    static void putPos(CompoundTag d,String key, ServerLevel level,BlockPos p) {
        d.putLong(key,p.asLong());
        d.putString(key+"Dim",dimension(level));
    }
    static boolean near(LivingEntity e,BlockPos p,double range) {
        double dx=e.getX()-(p.getX()+.5), dy=e.getY()-p.getY(), dz=e.getZ()-(p.getZ()+.5);
        return dx*dx+dy*dy+dz*dz<=range*range;
    }
    static boolean inArea(LivingEntity e,BlockPos p,int radius) {
        return DutyRules.inArea(e.getX()-(p.getX()+.5), e.getY()-p.getY(), e.getZ()-(p.getZ()+.5),radius);
    }
    static Raid raid(ServerLevel level, BlockPos center) {
        if (center == null || !level.hasChunkAt(center)) return null;
        Raid r=level.getRaidAt(center);
        return r!=null && r.isActive() && !r.isOver() && !r.isStopped() ? r : null;
    }
    public static boolean eligible(LumenCompanionEntity mob, LivingEntity target) {
        if (target==null || target==mob || !target.isAlive() || target.level()!=mob.level() || mob.isSleeping()) return false;
        if (data(mob).hasUUID("Leader")) {
            var id=data(mob).getUUID("Leader");
            if (id.equals(target.getUUID()) || target instanceof LumenCompanionEntity other
                    && data(other).hasUUID("Leader") && id.equals(data(other).getUUID("Leader"))) return false;
        }
        if (!guard(mob)) return true; // Existing unmodified follow/owner targeting still applies.
        if (!(mob.level() instanceof ServerLevel level)) return false;
        CompoundTag d=data(mob);
        BlockPos anchor=pos(d,"Anchor",level);
        var boss=leader(mob,level);
        if (boss!=null) anchor=boss.blockPosition();
        if (anchor==null || !inArea(target,anchor,DutyRules.radius(d.getInt("Radius")))
                || !inArea(mob,anchor,DutyRules.radius(d.getInt("Radius"))+4)) return false;
        if (village(mob)) {
            Raid r=raid(level,anchor);
            if (r!=null) return target instanceof Raider raider && raider.getCurrentRaid()==r;
        }
        return target instanceof Enemy && !(target instanceof LumenSeishinEntity);
    }
    public static LivingEntity guardOwner(LumenCompanionEntity mob) { return guard(mob)?null:mob.getOwner(); }
    public static boolean frostDamage(LumenCompanionEntity mob,LivingEntity target,ResourceKey<DamageType> type,float amount) {
        return hit(mob,target) && MistletoeDamage.hurt(mob,target,type,amount);
    }
    static LumenSeishinEntity leader(LumenCompanionEntity mob,ServerLevel level) {
        var d=data(mob);
        return d.hasUUID("Leader") && level.getEntity(d.getUUID("Leader")) instanceof LumenSeishinEntity boss
            && boss.isAlive()?boss:null;
    }
    public static boolean hit(LumenCompanionEntity mob,LivingEntity target) {
        return eligible(mob,target) && !mob.isOrderedToSit() && !mob.isVehicle()
                && (!guard(mob) || (mob.distanceToSqr(target)<=36 && mob.hasLineOfSight(target)));
    }
    public static boolean keepTarget(LumenCompanionEntity mob) { return eligible(mob,mob.getTarget()); }

    static BlockPos bedHead(ServerLevel level, BlockPos p) {
        if (p==null || !level.hasChunkAt(p)) return null;
        var state=level.getBlockState(p);
        if (!(state.getBlock() instanceof BedBlock)) return null;
        Direction facing=state.getValue(BedBlock.FACING);
        BlockPos head=state.getValue(BedBlock.PART)==BedPart.HEAD?p:p.relative(facing);
        BlockPos foot=head.relative(facing.getOpposite());
        if (!level.hasChunkAt(head) || !level.hasChunkAt(foot)) return null;
        var h=level.getBlockState(head);
        var f=level.getBlockState(foot);
        return h.getBlock()==state.getBlock() && f.getBlock()==state.getBlock()
                && h.getValue(BedBlock.PART)==BedPart.HEAD && f.getValue(BedBlock.PART)==BedPart.FOOT
                && h.getValue(BedBlock.FACING)==facing && f.getValue(BedBlock.FACING)==facing?head:null;
    }

    public static boolean playerInteract(LumenCompanionEntity mob, ServerPlayer player, InteractionHand hand) {
        if (hand!=InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || !mob.isTame()
                || !mob.isOwnedBy(player) || player.getItemInHand(hand).isEmpty()) return false;
        var item=player.getItemInHand(hand);
        if (item.is(Items.COMPASS)) {
            if (guard(mob)) { follow(mob,player.serverLevel()); say(player,"随从恢复跟随。再次使用指南针可重新选点。"); }
            else beginSelect(player,mob,"Anchor",24);
        }
        else if (item.is(Items.PAPER)) beginSelect(player,mob,"Bed",24);
        else return false;
        return true;
    }
    static void beginSelect(ServerPlayer player,LumenCompanionEntity mob,String op,int radius) {
        var p=new CompoundTag();
        p.putUUID("Mob",mob.getUUID()); p.putString("Operation",op);
        p.putString("Dim",dimension(player.serverLevel()));
        p.putLong("Until",player.serverLevel().getGameTime()+400);
        p.putInt("Radius",DutyRules.radius(radius));
        player.getPersistentData().put(SELECT,p);
        say(player, op.equals("Bed")?"请在 20 秒内右键床。":"请在 20 秒内右键驻守中心的方块。");
    }
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof LumenCompanionEntity mob)) return;
        if (playerInteract(mob, player, event.getHand())) {
            event.setCancellationResult(InteractionResult.SUCCESS); event.setCanceled(true);
        } else if ((village(mob) || data(mob).hasUUID("Leader")) && player.getItemInHand(event.getHand()).has(net.minecraft.core.component.DataComponents.FOOD)) {
            say(player,"这位随从有常驻职责，不能用食物驯服。");
            event.setCancellationResult(InteractionResult.SUCCESS); event.setCanceled(true);
        }
    }
    @SubscribeEvent
    public static void onSelect(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand()!=InteractionHand.MAIN_HAND) return;
        CompoundTag p=player.getPersistentData().getCompound(SELECT);
        if (!p.hasUUID("Mob")) return;
        var level=player.serverLevel();
        if (!dimension(level).equals(p.getString("Dim")) || level.getGameTime()>p.getLong("Until")
                || !(level.getEntity(p.getUUID("Mob")) instanceof LumenCompanionEntity mob)
                || !mob.isAlive() || !mob.isOwnedBy(player) || mob.distanceToSqr(player)>32*32) {
            player.getPersistentData().remove(SELECT);
            say(player,"选点已取消。随从不在附近，或已超过有效时间。"); return;
        }
        BlockPos clicked=event.getPos();
        if (!level.hasChunkAt(clicked) || !near(player,clicked,8) || !near(mob,clicked,32)) return;
        boolean bed=p.getString("Operation").equals("Bed");
        if (bed) {
            BlockPos head=bedHead(level,clicked);
            if (head==null) { say(player,"这里不是完整的床。"); return; }
            var d=data(mob); BlockPos old=pos(d,"Bed",level);
            if(!claimBed(level,head)){say(player,"这张床已经被村民占用了，换一张。");return; }
            releaseBed(level,old);
            putPos(d,"Bed",level,head); d.putBoolean("BedLocked",true); save(mob,d);
            say(player,"已强制绑定这张床（村民不会再认领它）。驻守将以床为中心，夜间会尝试回床。");
        } else {
            var post=VillageRespawns.findSafe(level,clicked.above(),2);
            if (post==null) { say(player,"方块周围没有可站立的位置。"); return; }
            setGuard(mob,level,post,p.getInt("Radius"));
            say(player,"随从开始驻守，半径 "+p.getInt("Radius")+" 格。");
        }
        player.getPersistentData().remove(SELECT);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
    static void say(ServerPlayer player,String msg){ player.sendSystemMessage(Component.literal(msg)); }
    static void setGuard(LumenCompanionEntity mob,ServerLevel level,BlockPos pos,int radius) {
        var d=data(mob); d.putBoolean("Guard",true);
        // 有绑定床时，驻守以床为中心（床优先于选点）。
        BlockPos bed=pos(d,"Bed",level);
        putPos(d,"Anchor",level,bed!=null?bed:pos); d.putInt("Radius",DutyRules.radius(radius)); save(mob,d);
        mob.setOrderedToSit(false); mob.setInSittingPose(false); reset(mob);
    }
    public static void follow(LumenCompanionEntity mob,ServerLevel level) {
        var d=data(mob); d.putBoolean("Guard",false); save(mob,d);
        mob.setOrderedToSit(false); mob.setInSittingPose(false); reset(mob);
    }
    static void reset(LumenCompanionEntity mob) {
        if (mob.isSleeping()) mob.stopSleeping();
        mob.setTarget(null); mob.getNavigation().stop();
    }

    public static class Follow extends FollowOwnerGoal {
        final LumenCompanionEntity mob;
        public Follow(LumenCompanionEntity mob,double speed,float start,float stop) { super(mob,speed,start,stop);this.mob=mob; }
        @Override public boolean canUse(){return !guard(mob)&&!mob.isSleeping()&&super.canUse();}
        @Override public boolean canContinueToUse(){return !guard(mob)&&!mob.isSleeping()&&super.canContinueToUse();}
    }
    public static class Stroll extends WaterAvoidingRandomStrollGoal {
        final LumenCompanionEntity mob;
        public Stroll(LumenCompanionEntity mob,double speed){super(mob,speed);this.mob=mob;}
        @Override public boolean canUse(){return !guard(mob)&&!mob.isSleeping()&&super.canUse();}
        @Override public boolean canContinueToUse(){return !guard(mob)&&!mob.isSleeping()&&super.canContinueToUse();}
    }
    public static class HomeGoal extends Goal {
        final LumenCompanionEntity mob;
        long nextPath;
        public HomeGoal(LumenCompanionEntity mob){this.mob=mob;setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){
            return guard(mob) && mob.isAlive() && !mob.isVehicle() && !mob.isOrderedToSit() && !keepTarget(mob);
        }
        @Override public boolean canContinueToUse(){return canUse();}
        @Override public void stop(){ mob.getNavigation().stop(); nextPath=0; }
        @Override public void tick() {
            if (!(mob.level() instanceof ServerLevel level) || mob.isSleeping()) return;
            var d=data(mob);
            BlockPos anchor=pos(d,"Anchor",level);
            var boss=leader(mob,level);
            if (boss!=null) {
                if (mob.distanceToSqr(boss)>36 && level.getGameTime()>=nextPath) {
                    nextPath=level.getGameTime()+20; mob.getNavigation().moveTo(boss,1.0);
                } else if (mob.distanceToSqr(boss)<=16) mob.getNavigation().stop();
                return;
            }
            if (anchor==null || !level.hasChunkAt(anchor)) return;
            Raid raid=village(mob)?raid(level,anchor):null;
            BlockPos post=raid!=null?pos(d,"Post",level):anchor;
            if(post==null || !level.hasChunkAt(post)) post=anchor;
            var bed=level.isNight() && raid==null?bedHead(level,pos(d,"Bed",level)):null;
            if (bed!=null && !level.getBlockState(bed).getValue(BedBlock.OCCUPIED) && near(mob,bed,2.2)) {
                mob.getNavigation().stop(); mob.startSleeping(bed); return;
            }
            if (level.getGameTime()<nextPath) return;
            nextPath=level.getGameTime()+20;
            if (bed!=null && !level.getBlockState(bed).getValue(BedBlock.OCCUPIED)) {
                for(Direction dir:Direction.Plane.HORIZONTAL) {
                    var p=bed.relative(dir);
                    if (level.hasChunkAt(p) && level.isEmptyBlock(p) && level.isEmptyBlock(p.above())
                            && !level.isEmptyBlock(p.below()) && go(p)) return;
                }
            }
            if (village(mob) && raid==null && !level.isNight() && !mob.getNavigation().isDone()) return;
            if (!near(mob,post,2.0)) go(post);
            else if (raid==null && village(mob) && !level.isNight() && mob.getNavigation().isDone()) {
                var p=anchor.offset(mob.getRandom().nextInt(13)-6,0,mob.getRandom().nextInt(13)-6);
                if(level.hasChunkAt(p) && level.isEmptyBlock(p) && level.isEmptyBlock(p.above())
                        && !level.isEmptyBlock(p.below())) go(p);
            }
        }
        boolean go(BlockPos p) { return mob.getNavigation().moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,1.0); }
    }
    public static void beforeAI(LumenCompanionEntity mob) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        // 援军由 RaidRelief.tickUnit 全权驱动，不走日常职责 AI
        if (RaidRelief.marked(mob)) return;
        // 没有绑定床时，低频自动认领附近没人用的床（纸可以强制覆盖）。
        if (mob.tickCount % 40 == 0 && !mob.isSleeping()) autoBindBed(mob,level);
        // 临时援军：按岗位（入口/中枢/村民/护送）分配不同的袭击者，避免全挤同一只。
        if (relief(mob) && mob.tickCount % 10 == 0 && !mob.isSleeping()) {
            LivingEntity current = mob.getTarget();
            if (current == null || !current.isAlive() || !eligible(mob, current)) {
                LivingEntity pick = pickReliefTarget(mob);
                if (pick != null) mob.setTarget(pick);
            }
        }
        if (mob.isSleeping()) {
            var d=data(mob);
            var target=mob.getTarget();
            if (!level.isNight() || !guard(mob) || mob.isOrderedToSit() || mob.isVehicle()
                    || target!=null && target.isAlive() || mob.hurtTime>0
                    || village(mob) && raid(level,pos(d,"Anchor",level))!=null
                    || bedHead(level,mob.getSleepingPos().orElse(null))==null) mob.stopSleeping();
        }
        if (mob.getTarget()!=null && !keepTarget(mob)) mob.setTarget(null);
    }

    /** 像村民一样占用床的 HOME POI：占上后村民就不会再认领这张床。 */
    static boolean claimBed(ServerLevel level,BlockPos head){
        return level.getPoiManager().take(h->h.is(PoiTypes.HOME),(h,p)->true,head,1).isPresent();
    }
    static void releaseBed(ServerLevel level,BlockPos head){
        if(head!=null)level.getPoiManager().release(head);
    }

    /** 自动认领附近一张“没人用”的完整床：不占村民的床、不抢其他随从已绑的床。 */
    static void autoBindBed(LumenCompanionEntity mob,ServerLevel level) {
        var d=data(mob);
        if (d.getBoolean("BedLocked") || pos(d,"Bed",level)!=null) return;
        BlockPos center=mob.blockPosition();
        for(BlockPos p:BlockPos.betweenClosed(center.offset(-16,-6,-16),center.offset(16,6,16))) {
            BlockPos head=bedHead(level,p);
            if (head==null) continue;
            if (level.getBlockState(head).getValue(BedBlock.OCCUPIED)) continue;
            if (bedClaimedByOther(mob,level,head)) continue;
            BlockPos old=pos(d,"Bed",level);
            if(!claimBed(level,head)) continue; // 已被村民占用，换一张
            releaseBed(level,old);
            putPos(d,"Bed",level,head); save(mob,d);
            if (d.getBoolean("Guard")) { putPos(d,"Anchor",level,head); save(mob,d); }
            return;
        }
    }
    static boolean villagerNear(ServerLevel level,BlockPos p,double range) {
        return !level.getEntitiesOfClass(net.minecraft.world.entity.npc.AbstractVillager.class,
                new net.minecraft.world.phys.AABB(p).inflate(range)).isEmpty();
    }
    static boolean bedClaimedByOther(LumenCompanionEntity mob,ServerLevel level,BlockPos head) {
        for(var other:level.getEntitiesOfClass(LumenCompanionEntity.class,new net.minecraft.world.phys.AABB(head).inflate(24))) {
            if (other==mob) continue;
            BlockPos b=pos(data(other),"Bed",level);
            if (head.equals(b)) return true;
        }
        return false;
    }
    /** 按岗位给临时援军挑目标：入口按岗位就近、中枢守钟、村民护村民、护送盯中心。 */
    static LivingEntity pickReliefTarget(LumenCompanionEntity mob) {
        if (!(mob.level() instanceof ServerLevel level)) return null;
        var d = data(mob);
        BlockPos anchor = pos(d, "Anchor", level);
        Raid raid = raid(level, anchor);
        if (raid == null) return null;
        var raiders = new java.util.ArrayList<Raider>();
        for (Raider r : raid.getAllRaiders()) {
            if (r.isAlive() && r.level() == level) raiders.add(r);
        }
        if (raiders.isEmpty()) return null;
        BlockPos post = pos(d, "Post", level);
        int slot = d.getInt("ReliefSlot");
        String role = d.getString("ReliefRole");
        return switch (role) {
            case "PERIMETER" -> {
                if (post == null) yield raiders.get(slot % raiders.size());
                raiders.sort(java.util.Comparator.comparingDouble(r -> r.distanceToSqr(
                        post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D)));
                yield raiders.get(slot % raiders.size());
            }
            case "CENTRAL" -> {
                raiders.sort(java.util.Comparator.comparingDouble(r -> r.distanceToSqr(
                        anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D)));
                yield raiders.get(slot % raiders.size());
            }
            case "VILLAGERS" -> {
                var villagers = level.getEntitiesOfClass(net.minecraft.world.entity.npc.AbstractVillager.class,
                        new net.minecraft.world.phys.AABB(anchor).inflate(48));
                if (villagers.isEmpty()) {
                    raiders.sort(java.util.Comparator.comparingDouble(r -> r.distanceToSqr(
                            anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D)));
                } else {
                    var v = villagers.get(0);
                    raiders.sort(java.util.Comparator.comparingDouble(r -> r.distanceToSqr(v)));
                }
                yield raiders.get(slot % raiders.size());
            }
            case "ESCORT" -> {
                raiders.sort(java.util.Comparator.comparingDouble(r -> r.distanceToSqr(
                        raid.getCenter().getX() + 0.5D, raid.getCenter().getY(),
                        raid.getCenter().getZ() + 0.5D)));
                yield raiders.get(slot % raiders.size());
            }
            default -> raiders.get(slot % raiders.size());
        };
    }

    /** UI 的「驻守」按钮：有床直接以床为中心驻守；没床进入选点流程。 */
    public static boolean garrisonFromUi(ServerPlayer player,LumenCompanionEntity mob) {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        var d=data(mob); BlockPos bed=pos(d,"Bed",level);
        if (bed!=null) { setGuard(mob,level,bed,DutyRules.radius(d.getInt("Radius"))); say(player,"随从开始以床为中心驻守。"); return true; }
        beginSelect(player,mob,"Anchor",24);
        say(player,"还没有床，请在 20 秒内右键驻守中心的方块。");
        return true;
    }
    private CompanionDuty() {}
}
