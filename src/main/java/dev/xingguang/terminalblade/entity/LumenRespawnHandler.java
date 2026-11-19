package dev.xingguang.terminalblade.entity;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.duty.RaidRelief;
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
 * {@code getRespawnPosition()} / {@code getRespawnDimension()}；主人没设重生点就落在主人脚下。
 * 主人不在线时保持等待。</p>
 *
 * <p>待复活队列持久化在 {@link LumenRespawnData}（世界存档），关档/重启不会丢。
 * 每只随从单独一条记录，并保存死亡时的完整实体 NBT，复活后职责（驻守/床位/门绑定）与血量都还在。</p>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class LumenRespawnHandler {
    private static final int RESPAWN_DELAY_TICKS = 200;

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
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        LumenRespawnData data = LumenRespawnData.get(server);
        if (data.entries().isEmpty()) {
            return;
        }
        // 复制一份 key，避免遍历时 remove 触发并发修改
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
        BlockPos respawnPos = owner.getRespawnPosition();
        ServerLevel target = dimension == null ? null : server.getLevel(dimension);
        if (target == null || respawnPos == null) {
            target = owner.serverLevel();
            respawnPos = owner.blockPosition();
        }

        LumenCompanionEntity entity = ModEntities.LUMEN_COMPANION.get().create(target);
        if (entity == null) {
            return true;
        }
        if (pending.snapshot() != null) {
            // 还原死亡时的完整状态：职责数据、驻守/床位、名字、朝向都在里面。
            // 注意：快照是「死亡瞬间」的，Health=0、Motion 可能是坠落速度、可能还带着火，
            // 直接加载会让它复活即死（之前就是这个 bug），所以下面统一把状态清干净。
            try {
                entity.load(pending.snapshot());
            } catch (RuntimeException e) {
                TerminalBlade.LOGGER.warn("[流明·星] 随从 {} 的复活快照无法读取，退回基础属性", petId, e);
            }
            entity.setHealth(entity.getMaxHealth());
            entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
            entity.setRemainingFireTicks(0);
            entity.fallDistance = 0.0F;
            entity.hurtTime = 0;
            entity.deathTime = 0;
            entity.invulnerableTime = 0;
        }
        // 重生点可能正好在床上 / 被方块或液体占住：向上找能站的位置；
        // 重生点实在放不下就退到主人脚下，避免复活进岩浆/虚空再死一次。
        BlockPos spot = safeSpot(target, respawnPos);
        if (spot == null) {
            spot = safeSpot(target, owner.blockPosition());
        }
        if (spot == null) {
            TerminalBlade.LOGGER.warn("[流明·星] 随从 {} 找不到安全复活点（重生点={}），继续等待",
                    petId, respawnPos);
            return false;
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
        target.addFreshEntity(entity);
        owner.sendSystemMessage(Component.translatable("entity.mistletoe.lumen_companion.respawned"));
        TerminalBlade.LOGGER.info("[流明·星] 随从在主人重生点复活：{}（快照={}）",
                respawnPos, pending.snapshot() != null);
        return true;
    }

    /** 从给定位置向上找 12 格内能站人的位置；找不到返回 null。 */
    private static BlockPos safeSpot(ServerLevel level, BlockPos from) {
        if (from == null) {
            return null;
        }
        for (int dy = 0; dy < 12; dy++) {
            BlockPos feet = from.above(dy);
            if (level.isEmptyBlock(feet) && level.isEmptyBlock(feet.above())
                    && !level.isEmptyBlock(feet.below())) {
                return feet;
            }
        }
        return null;
    }
}
