package dev.xingguang.terminalblade.entity;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.duty.CompanionTracker;
import dev.xingguang.terminalblade.duty.RaidRelief;
import dev.xingguang.terminalblade.duty.SafeSpots;
import dev.xingguang.terminalblade.registry.ModEntities;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 已臣服的随从死亡后，像玩家一样在主人的重生点重生。
 *
 * <p>延迟 {@link #RESPAWN_DELAY_TICKS} tick 后，取主人当前的
 * 先找主人的重生点，找不到安全位置时改到主人当前维度寻找。
 * 主人离线或本次复活失败时保留记录，每 20 tick 检查一次。</p>
 *
 * <p>待复活队列持久化在 {@link LumenRespawnData}（世界存档），关档/重启不会丢。
 * 每只随从单独一条记录，复活时从死亡快照还原职责（驻守/床位/门绑定），生命值恢复至上限。</p>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class LumenRespawnHandler {
    private static final int RESPAWN_DELAY_TICKS = 200;
    private static final int RETRY_INTERVAL_TICKS = 20;

    private LumenRespawnHandler() {
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof LumenCompanionEntity pet) || pet.level().isClientSide()) {
            return;
        }
        if (!pet.isTame()) {
            return;
        }
        // 临时援军单位不进复活队列：它们不是玩家的宠物，死亡就结束。
        if (RaidRelief.marked(pet)) {
            return;
        }
        UUID owner = pet.getOwnerUUID();
        MinecraftServer server = pet.level().getServer();
        if (owner == null || server == null) {
            return;
        }
        CompoundTag snapshot = pet.saveWithoutId(new CompoundTag());
        LumenRespawnData.get(server).add(pet.getUUID(), owner,
                pet.level().getGameTime() + RESPAWN_DELAY_TICKS,
                pet.getName().getString(), snapshot);
        // 死亡后位置不再有效，避免跟随逻辑去读一个已经空掉的区块。
        CompanionTracker.get(server).remove(pet.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.overworld().getGameTime() % RETRY_INTERVAL_TICKS != 0) {
            return;
        }
        LumenRespawnData data = LumenRespawnData.get(server);
        if (data.entries().isEmpty()) {
            return;
        }
        // 遍历快照，成功复活的记录可立即从队列移除。
        for (Map.Entry<UUID, LumenRespawnData.Entry> pending : new ArrayList<>(data.entries().entrySet())) {
            if (tryRespawn(server, pending.getKey(), pending.getValue())) {
                data.remove(pending.getKey());
            }
        }
    }

    private static boolean tryRespawn(MinecraftServer server, UUID petId, LumenRespawnData.Entry pending) {
        if (server.overworld().getGameTime() < pending.dueTick()) {
            return false;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(pending.owner());
        if (owner == null) {
            // 主人不在线：等他回来再重生
            return false;
        }

        ResourceKey<Level> dimension = owner.getRespawnDimension();
        ServerLevel target = dimension == null ? null : server.getLevel(dimension);
        BlockPos spot = target == null ? null : SafeSpots.find(target, owner.getRespawnPosition());
        if (spot == null) {
            target = owner.serverLevel();
            spot = SafeSpots.find(target, owner.blockPosition());
        }
        if (spot == null) {
            return false;
        }

        LumenCompanionEntity entity = ModEntities.LUMEN_COMPANION.get().create(target);
        if (entity == null) {
            return false;
        }
        if (pending.snapshot() != null) {
            // 还原死亡时的完整状态：职责数据、驻守/床位、名字、朝向都在里面。
            // 注意：快照是「死亡瞬间」的，Health=0、Motion 可能是坠落速度、可能还带着火，
            // 直接加载会让它复活即死（之前就是这个 bug），所以下面统一把状态清干净。
            try {
                entity.load(pending.snapshot());
            } catch (RuntimeException e) {
                TerminalBlade.LOGGER.debug("[流明·星] 随从 {} 的复活快照无法完整读取", petId, e);
            }
            entity.setHealth(entity.getMaxHealth());
            entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
            entity.setRemainingFireTicks(0);
            entity.fallDistance = 0.0F;
            entity.hurtTime = 0;
            entity.deathTime = 0;
            entity.invulnerableTime = 0;
        }
        double x = spot.getX() + 0.5D;
        double y = spot.getY();
        double z = spot.getZ() + 0.5D;
        entity.moveTo(x, y, z, owner.getYRot(), 0.0F);
        entity.setTame(true, true);
        entity.setOwnerUUID(pending.owner());
        if (pending.snapshot() == null) {
            // 旧档迁移记录：没有快照，只能按默认属性重建
            entity.setCustomName(Component.literal(pending.name()));
            var maxHealth = entity.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(LumenCompanionEntity.MAX_HEALTH);
            }
            entity.setHealth(LumenCompanionEntity.MAX_HEALTH);
        }
        if (!target.addFreshEntity(entity)) {
            return false;
        }
        owner.sendSystemMessage(Component.translatable("entity.mistletoe.lumen_companion.respawned"));
        TerminalBlade.LOGGER.info("[流明·星] 随从在 {} 复活（快照={}）", spot, pending.snapshot() != null);
        return true;
    }
}
