package dev.xingguang.terminalblade.duty;

import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import dev.xingguang.terminalblade.entity.LumenSeishinEntity;

@EventBusSubscriber(modid="mistletoe")
public final class DutyCommands {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(literal("lumen_duty")
            .then(literal("follow").then(argument("companion",EntityArgument.entity()).executes(c->{
                ServerPlayer p=c.getSource().getPlayerOrException();
                var mob=controlled(c.getSource(),p,EntityArgument.getEntity(c,"companion"));
                if(mob==null)return 0;
                CompanionDuty.follow(mob,p.serverLevel()); CompanionDuty.say(p,"随从恢复跟随。"); return 1;
            })))
            .then(literal("status").then(argument("companion",EntityArgument.entity()).executes(c->{
                ServerPlayer p=c.getSource().getPlayerOrException();
                var mob=controlled(c.getSource(),p,EntityArgument.getEntity(c,"companion"));
                if(mob==null)return 0;
                var d=CompanionDuty.data(mob);
                CompanionDuty.say(p,"驻守="+CompanionDuty.guard(mob)+"，床="+CompanionDuty.pos(d,"Bed",p.serverLevel())
                    +"，中心="+CompanionDuty.pos(d,"Anchor",p.serverLevel())+"，睡眠="+mob.isSleeping());
                return 1;
            })))
            .then(literal("guard").then(argument("companion",EntityArgument.entity())
                .then(argument("radius",IntegerArgumentType.integer(12,40)).executes(c->{
                    ServerPlayer p=c.getSource().getPlayerOrException();
                    var mob=controlled(c.getSource(),p,EntityArgument.getEntity(c,"companion"));
                    if(mob==null)return 0;
                    int radius=IntegerArgumentType.getInteger(c,"radius");
                    if(radius!=DutyRules.radius(radius)){fail(c.getSource(),"半径只支持 12、24、40。");return 0;}
                    CompanionDuty.beginSelect(p,mob,"Anchor",radius); return 1;
                }))))
            .then(literal("village").requires(s->s.hasPermission(2)).then(argument("companion",EntityArgument.entity())
                .then(argument("center",BlockPosArgument.blockPos()).then(argument("post",BlockPosArgument.blockPos()).executes(c->{
                    if(!(EntityArgument.getEntity(c,"companion") instanceof LumenCompanionEntity mob) || mob.isTame()){
                        fail(c.getSource(),"请选择未驯服的随从。");return 0;
                    }
                    var level=c.getSource().getLevel();
                    var center=BlockPosArgument.getBlockPos(c,"center"); var post=BlockPosArgument.getBlockPos(c,"post");
                    if (!level.hasChunkAt(center) || !level.hasChunkAt(post)
                        || !DutyRules.inArea(post.getX()-center.getX(),post.getY()-center.getY(),post.getZ()-center.getZ(),24)
                        || !level.isEmptyBlock(post) || !level.isEmptyBlock(post.above()) || level.isEmptyBlock(post.below())) {
                        fail(c.getSource(),"中心和岗位须已加载，且岗位要在 24 格内、有站立空间。");return 0;
                    }
                    var d=CompanionDuty.data(mob); d.putBoolean("Village",true);
                    CompanionDuty.putPos(d,"Post",level,post); CompanionDuty.save(mob,d);
                    CompanionDuty.setGuard(mob,level,center,24);
                    c.getSource().sendSuccess(()->Component.literal("已登记村庄守卫。"),false); return 1;
                })))))
            .then(literal("village_bed").requires(s->s.hasPermission(2)).then(argument("companion",EntityArgument.entity())
                .then(argument("bed",BlockPosArgument.blockPos()).executes(c->{
                    if (!(EntityArgument.getEntity(c,"companion") instanceof LumenCompanionEntity mob) || !CompanionDuty.village(mob)) {
                        fail(c.getSource(),"请选择已登记的村庄守卫。");return 0;
                    }
                    var level=c.getSource().getLevel();
                    var d=CompanionDuty.data(mob); var anchor=CompanionDuty.pos(d,"Anchor",level);
                    var head=CompanionDuty.bedHead(level,BlockPosArgument.getBlockPos(c,"bed"));
                    if(head==null || anchor==null || !DutyRules.inArea(head.getX()-anchor.getX(),head.getY()-anchor.getY(),head.getZ()-anchor.getZ(),24)){
                        fail(c.getSource(),"须选择本村防区内的一张完整床。");return 0;
                    }
                    CompanionDuty.putPos(d,"Bed",level,head); CompanionDuty.save(mob,d);
                    c.getSource().sendSuccess(()->Component.literal("已分配村庄守卫的床。"),false); return 1;
                }))))
            .then(literal("leader").requires(s->s.hasPermission(2)).then(argument("companion",EntityArgument.entity())
                .then(argument("boss",EntityArgument.entity()).executes(c->{
                    if(!(EntityArgument.getEntity(c,"companion") instanceof LumenCompanionEntity mob)
                        || mob.isTame() || CompanionDuty.village(mob)
                        || !(EntityArgument.getEntity(c,"boss") instanceof LumenSeishinEntity boss)){
                        fail(c.getSource(),"请选择普通无主随从和一位流明·星。");return 0;
                    }
                    var d=CompanionDuty.data(mob); d.putUUID("Leader",boss.getUUID()); CompanionDuty.save(mob,d);
                    CompanionDuty.setGuard(mob,mob.level() instanceof net.minecraft.server.level.ServerLevel l?l:c.getSource().getLevel(),mob.blockPosition(),24);
                    c.getSource().sendSuccess(()->Component.literal("随从已绑定流明领队。"),false);return 1;
                }))))
            .then(literal("relief").requires(s->s.hasPermission(2)).executes(c->{
                var p=c.getSource().getPlayerOrException();
                var level=p.serverLevel();
                var bell=level.getPoiManager().findClosest(h->h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING),
                        p.blockPosition(),64,net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY);
                if(bell.isEmpty()){fail(c.getSource(),"附近 64 格内没有钟。");return 0;}
                var raid=level.getRaids().getNearbyRaid(bell.get(),128);
                if(raid==null){fail(c.getSource(),"钟附近没有进行中的袭击。");return 0;}
                int waves=raid.getNumGroups(level.getDifficulty());
                c.getSource().sendSuccess(()->Component.literal(
                        "袭击#"+raid.getId()+" active="+raid.isActive()+" over="+raid.isOver()
                        +" omen="+raid.getRaidOmenLevel()+" wave="+raid.getGroupsSpawned()+"/"+waves
                        +" alive="+raid.getTotalRaidersAlive()+" 中心="+raid.getCenter()),false);
                RaidRelief.call(p);
                c.getSource().sendSuccess(()->Component.literal("已尝试求援，看聊天提示。"),false);
                return 1;
            }))
            .then(literal("wave").requires(s->s.hasPermission(2)).executes(c->{
                var p=c.getSource().getPlayerOrException();
                var raid=CompanionDuty.raid(p.serverLevel(),p.blockPosition());
                var wave=raid==null?null:WaveLog.last(raid);
                if(wave==null){fail(c.getSource(),"附近没有本次开服已记录的袭击波。");return 0;}
                c.getSource().sendSuccess(()->Component.literal("袭击 #"+raid.getId()+" 第 "+wave.number()+" 波落点 "+wave.pos()),false);
                return 1;
            })));
    }
    static LumenCompanionEntity controlled(CommandSourceStack source,ServerPlayer p,net.minecraft.world.entity.Entity target){
        if(target instanceof LumenCompanionEntity mob && mob.isAlive() && mob.isOwnedBy(p)
            && mob.level()==p.level() && mob.distanceToSqr(p)<=32*32) return mob;
        fail(source,"请选择 32 格内属于你的随从。"); return null;
    }
    static void fail(CommandSourceStack source,String msg){source.sendFailure(Component.literal(msg));}
    private DutyCommands() {}
}
