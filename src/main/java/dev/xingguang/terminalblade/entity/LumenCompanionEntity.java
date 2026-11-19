package dev.xingguang.terminalblade.entity;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.duty.CompanionDuty;
import dev.xingguang.terminalblade.registry.ModSounds;
import dev.xingguang.terminalblade.combat.MistletoeDamage;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import mods.flammpfeil.slashblade.registry.slashblade.SlashBladeDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 臣服后的流明·星「随从」形态。
 *
 * <p>和 Boss（{@link LumenSeishinEntity}）是两个独立实体：Boss 被玩家打死时消失并在原地生成它。
 * 它固定使用暮色一套（刀、招式、伤害），没有终局蓄力，也不会自爆。</p>
 */
public class LumenCompanionEntity extends TamableAnimal implements GeoAnimatable {
    public static final float MAX_HEALTH = 20.0F;
    /** 下界合金全套：护甲 20。 */
    private static final double ARMOR = 20.0D;
    /** 下界合金全套：盔甲韧性 12。 */
    private static final double ARMOR_TOUGHNESS = 12.0D;
    private static final double MOVEMENT_SPEED = 0.30D;
    private static final double KNOCKBACK_RESISTANCE = 0.50D;
    private static final double FOLLOW_RANGE = 24.0D;
    private static final int MELEE_CHAIN = 4;
    private static final int MELEE_INTERVAL = 3;
    /** 目标被骑乘/卡路径导致原生近战够不到时，每隔这么多 tick 自己起一段连段。 */
    private static final long MELEE_STUCK_RETRY = 20L;
    private static final double MELEE_STUCK_RANGE = 6.0D;
    private static final long SLASH_WINDUP = 24L;
    private static final long SLASH_COOLDOWN = 240L;
    private static final double SLASH_RANGE = 8.0D;
    private static final double MELEE_HIT_RANGE = 4.25D;
    /** 普攻连段的顺劈扇形（以朝目标的方向为轴）。 */
    private static final double MELEE_CLEAVE_CONE_DEGREES = 120.0D;
    private static final double OWNER_GUARD_RADIUS = 24.0D;
    private static final int SLASH_COLOR = 0xA9D8FF;

    /** 初雪霜刃：7 刀 × 2，前方 100°、6 格。 */
    private static final int FROST_HITS = 7;
    private static final float FROST_DAMAGE = 2.0F;
    private static final double FROST_RANGE = 6.0D;
    private static final double FROST_CONE_DEGREES = 100.0D;
    private static final long FROST_HIT_INTERVAL = 2L;
    private static final float FROST_MELEE_DAMAGE = 6.0F;

    private static final int REGEN_INTERVAL = 40;
    private static final int INVENTORY_SIZE = 36;

    /**
     * 全套保护 IV 的减伤系数。
     * 保护 IV 每件 4 EPF，四件 16 EPF → 减伤 16/25 = 64% → 只受 36% 伤害。
     */
    private static final float PROTECTION_FACTOR = 0.36F;

    private static final EntityDataAccessor<Boolean> DATA_LANTERN =
            SynchedEntityData.defineId(LumenCompanionEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> DATA_ACTION =
            SynchedEntityData.defineId(LumenCompanionEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_ANIM_SEQ =
            SynchedEntityData.defineId(LumenCompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_COMBO_INDEX =
            SynchedEntityData.defineId(LumenCompanionEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);
    private final net.minecraft.world.SimpleContainer inventory = new net.minecraft.world.SimpleContainer(INVENTORY_SIZE);

    private int lastAnimSeq = -1;
    private String lastAnimName = "";

    private LivingEntity meleeTarget;
    private int meleeHitsLeft;
    private int meleeHitIndex;
    private int meleeCooldownTicks;
    /** 上一次连段起手的 game time：用于"锁着目标却够不到"的僵局兜底。 */
    private long lastMeleeStart;

    private int actionTicksLeft;
    /** 动作是否被 {@link #holdAction} 锁住（不自动到期）。 */
    private boolean actionHeld;

    private long slashWindupUntil;
    private float slashLockedYaw;
    private long slashCooldownUntil;
    /** 初雪霜刃：剩余刀数、当前刀、下一刀时间、起手快照目标。 */
    private int frostHitsLeft;
    private int frostHitIndex;
    private long nextFrostTick;
    private java.util.List<LivingEntity> frostTargets = java.util.List.of();

    public LumenCompanionEntity(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return net.minecraft.world.entity.Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, MAX_HEALTH)
                .add(Attributes.ARMOR, ARMOR)
                .add(Attributes.ARMOR_TOUGHNESS, ARMOR_TOUGHNESS)
                .add(Attributes.MOVEMENT_SPEED, MOVEMENT_SPEED)
                .add(Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE)
                .add(Attributes.FOLLOW_RANGE, FOLLOW_RANGE)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.SWEEPING_DAMAGE_RATIO, 0.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, (byte) LumenAction.NONE.ordinal());
        builder.define(DATA_ANIM_SEQ, 0);
        builder.define(DATA_COMBO_INDEX, 0);
        builder.define(DATA_LANTERN, false);
    }

    public LumenAction getAction() {
        return LumenAction.byOrdinal(this.entityData.get(DATA_ACTION));
    }

    public int getAnimSeq() {
        return this.entityData.get(DATA_ANIM_SEQ);
    }

    private void playAction(LumenAction action) {
        if (this.level().isClientSide() || action == null) {
            return;
        }
        this.actionHeld = false; // 新动作覆盖之前锁住的持续动作
        this.entityData.set(DATA_ACTION, (byte) action.ordinal());
        this.entityData.set(DATA_ANIM_SEQ, this.entityData.get(DATA_ANIM_SEQ) + 1);
        this.actionTicksLeft = switch (action) {
            case MELEE -> 14;
            case SLASH_ART -> 30;
            case FROST -> 30;
            default -> 14;
        };
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SitWhenOrderedToGoal(this));
        this.goalSelector.addGoal(2, new LumenFootworkGoal(this, () -> this.isOrderedToSit()
                || this.isVehicle() || this.meleeHitsLeft > 0 || this.slashWindupUntil > 0L
                || !shouldGuardTarget(getTarget())));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(4, new CompanionDuty.Follow(this, 1.0D, 10.0F, 3.0F));
        // 职责：驻守/回床/回岗（只在有职责时接管移动）
        this.goalSelector.addGoal(5, new CompanionDuty.HomeGoal(this));
        this.goalSelector.addGoal(7, new CompanionDuty.Stroll(this, 0.8D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 16.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new OwnerHurtByTargetGoal(this));
        this.targetSelector.addGoal(3, new OwnerHurtTargetGoal(this));
        // 主动搜寻敌对生物（Enemy 标记接口），不会误打牛羊
        // 主动搜寻敌对生物（Enemy 标记接口），不会误打牛羊；未收服时同样会打
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<net.minecraft.world.entity.Mob>(
                this, net.minecraft.world.entity.Mob.class, 10, true, false,
                target -> target != this && target instanceof Enemy
                        && !(target instanceof LumenSeishinEntity)
                        && !this.isAlliedTo(target)));
    }

    // ── 每 tick ─────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) {
            if (this.tickCount == 1) {
                refreshBlade();
            }
            tickActionLifetime();
            tickMeleeChain();
            tickLantern();
        }
    }

    /** 是否处于「举灯」状态：由服务端按亮度滞回判定并同步，客户端/服务端看到的一致。 */
    public boolean isLanternOut() {
        return this.entityData.get(DATA_LANTERN);
    }

    /**
     * 黑暗时把灯笼放进副手（配合动态光源模组发光），变亮时收起。
     * 用滞回（进入 <7、退出 >=12）避免站在亮度阈值上来回闪。
     */
    private void tickLantern() {
        boolean lit = this.entityData.get(DATA_LANTERN);
        int light = this.level().getMaxLocalRawBrightness(this.blockPosition());
        boolean shouldLit = lit ? light < 12 : light < 7;
        if (shouldLit == lit) {
            return;
        }
        this.entityData.set(DATA_LANTERN, shouldLit);
        ItemStack off = this.getItemBySlot(EquipmentSlot.OFFHAND);
        if (shouldLit) {
            if (off.isEmpty()) {
                this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.LANTERN));
            }
        } else if (off.is(Items.LANTERN)) {
            this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }
    }

    private void tickActionLifetime() {
        if (this.actionHeld || this.actionTicksLeft <= 0) {
            return; // 持续动作（如援军返航的迅冲斩）由调用方显式解除
        }
        if (--this.actionTicksLeft <= 0) {
            this.entityData.set(DATA_ACTION, (byte) LumenAction.NONE.ordinal());
        }
    }

    /**
     * 锁住一个**持续型**动作：不会被 {@code actionTicksLeft} 自动清掉，
     * 需要调用 {@link #clearHoldAction()} 才会结束。援军返航的连续迅冲斩用它。
     */
    public void holdAction(LumenAction action) {
        if (this.level().isClientSide() || action == null) {
            return;
        }
        this.actionHeld = true;
        this.actionTicksLeft = Integer.MAX_VALUE;
        this.entityData.set(DATA_ACTION, (byte) action.ordinal());
        this.entityData.set(DATA_ANIM_SEQ, this.entityData.get(DATA_ANIM_SEQ) + 1);
    }

    /** 解除 {@link #holdAction} 锁住的动作，回到普通状态。 */
    public void clearHoldAction() {
        if (this.level().isClientSide() || !this.actionHeld) {
            return;
        }
        this.actionHeld = false;
        this.actionTicksLeft = 0;
        this.entityData.set(DATA_ACTION, (byte) LumenAction.NONE.ordinal());
        this.entityData.set(DATA_ANIM_SEQ, this.entityData.get(DATA_ANIM_SEQ) + 1);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide() && dev.xingguang.terminalblade.duty.RaidRelief.marked(this)) {
            dev.xingguang.terminalblade.duty.RaidRelief.tickUnit(this);
            return;
        }
        CompanionDuty.beforeAI(this);
        if (this.level().isClientSide()) {
            return;
        }
        if (this.isTame() && getHealth() < getMaxHealth() && this.tickCount % REGEN_INTERVAL == 0) {
            this.heal(1.0F);
        }
        tickSkills();
    }

    private void tickSkills() {
        LivingEntity target = getTarget();
        if (this.isOrderedToSit() || this.isVehicle() || !shouldGuardTarget(target)) {
            this.slashWindupUntil = 0L;
            this.meleeHitsLeft = 0;
            if (target != null && (!shouldGuardTarget(target) || this.isOrderedToSit())) {
                setTarget(null);
            }
            return;
        }
        if (target == null || !target.isAlive()) {
            this.slashWindupUntil = 0L;
            return;
        }
        long now = this.level().getGameTime();
        if (this.frostHitsLeft > 0) {
            tickFrost();
            return;
        }
        if (this.slashWindupUntil > 0L) {
            if (now >= this.slashWindupUntil) {
                this.slashWindupUntil = 0L;
                strikeWithYaw(this.slashLockedYaw);
            }
            return;
        }
        if (now >= this.slashCooldownUntil && distanceToSqr(target) <= SLASH_RANGE * SLASH_RANGE) {
            this.meleeHitsLeft = 0;
            faceTarget(target);
            this.slashLockedYaw = getYRot();
            this.slashWindupUntil = now + SLASH_WINDUP;
            this.slashCooldownUntil = now + SLASH_COOLDOWN;
            playAction(LumenAction.FROST);
            playSound(SoundEvents.BEACON_ACTIVATE, 1.0F, 1.2F);
        }
    }

    // ── 普攻连段 ────────────────────────────────────────────────────────

    @Override
    public boolean doHurtTarget(Entity target) {
        if (this.level().isClientSide() || this.isOrderedToSit() || this.isVehicle()
                || this.meleeHitsLeft > 0 || this.slashWindupUntil > 0L
                || !(target instanceof LivingEntity living) || !shouldGuardTarget(living)) {
            return false;
        }
        this.meleeTarget = living;
        this.meleeHitIndex = 0;
        this.lastMeleeStart = this.level().getGameTime();
        this.meleeHitsLeft = MELEE_CHAIN;
        this.meleeCooldownTicks = 0;
        meleeHit();
        return true;
    }

    private void tickMeleeChain() {
        if (this.meleeHitsLeft <= 0) {
            tryUnstuckMelee();
            return;
        }
        if (this.slashWindupUntil > 0L || this.isOrderedToSit() || this.isVehicle()) {
            return;
        }
        if (--this.meleeCooldownTicks <= 0) {
            meleeHit();
        }
    }

    /** 目标骑在坐骑上时原生近战够不到：每 1 秒自己起一段连段，避免锁着目标干站着。 */
    private void tryUnstuckMelee() {
        LivingEntity target = getTarget();
        if (target == null || target == this || !target.isAlive()) {
            return;
        }
        if (this.isOrderedToSit() || this.isVehicle() || this.slashWindupUntil > 0L) {
            return;
        }
        if (this.level().getGameTime() - this.lastMeleeStart < MELEE_STUCK_RETRY) {
            return;
        }
        if (distanceToSqr(target) > MELEE_STUCK_RANGE * MELEE_STUCK_RANGE) {
            return;
        }
        if (!shouldGuardTarget(target)) {
            return;
        }
        if (!target.isPassenger() && !hasLineOfSight(target)) {
            return;
        }
        this.meleeTarget = target;
        this.meleeHitIndex = 0;
        this.lastMeleeStart = this.level().getGameTime();
        this.meleeHitsLeft = MELEE_CHAIN;
        this.meleeCooldownTicks = 0;
        meleeHit();
    }

    /** 初雪普攻：固定 6 点，不套暮色的生命差公式。 */
    private float meleeAmount(LivingEntity target) {
        return FROST_MELEE_DAMAGE;
    }

    private void meleeHit() {
        LivingEntity target = this.meleeTarget;
        if (!shouldGuardTarget(target)) {
            this.meleeHitsLeft = 0;
            this.meleeTarget = null;
            return;
        }
        this.entityData.set(DATA_COMBO_INDEX, this.meleeHitIndex + 1);
        playAction(LumenAction.MELEE);
        spawnCosmeticSlash(-25.0F + getRandom().nextFloat() * 50.0F, 1.8F, SLASH_COLOR);
        // 顺劈：以朝目标方向为轴、近战范围（4.25 格、120°）内的敌人一起结算。
        Vec3 towardTarget = new Vec3(target.getX() - getX(), 0.0D, target.getZ() - getZ());
        for (LivingEntity victim : fanTargets(MELEE_HIT_RANGE, MELEE_CLEAVE_CONE_DEGREES, towardTarget)) {
            MistletoeDamage.hurt(this, victim, MistletoeDamage.SLASH, meleeAmount(victim));
        }
        playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 0.8F, 1.0F);
        this.meleeHitIndex++;
        this.meleeHitsLeft--;
        this.meleeCooldownTicks = MELEE_INTERVAL;
    }

    public void slashStrike(LivingEntity target) {
        if (target != null && target.isAlive()) {
            faceTarget(target);
        }
        playAction(LumenAction.FROST);
        strikeWithYaw(getYRot());
    }

    /**
     * 初雪霜刃起手：以锁定朝向为轴，6 格 / 100° 扇形内快照目标，然后 7 刀 ×2 快速结算。
     */
    private void strikeWithYaw(float lockedYaw) {
        spawnCosmeticSlash(-30.0F, 2.6F, SLASH_COLOR);
        Vec3 forward = new Vec3(-Math.sin(Math.toRadians(lockedYaw)), 0.0D,
                Math.cos(Math.toRadians(lockedYaw)));
        this.frostTargets = fanTargets(FROST_RANGE, FROST_CONE_DEGREES, forward);
        this.frostHitsLeft = FROST_HITS;
        this.frostHitIndex = 0;
        this.nextFrostTick = this.level().getGameTime();
        playAction(LumenAction.FROST);
        playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.4F);
        playSound(SoundEvents.GLASS_BREAK, 0.35F, 1.65F);
    }

    /** 霜刃逐刀结算：每 2 tick 一刀，目标被击退也不会丢刀（起手快照）。 */
    private void tickFrost() {
        long now = this.level().getGameTime();
        if (now < this.nextFrostTick) {
            return;
        }
        this.nextFrostTick = now + FROST_HIT_INTERVAL;
        this.frostHitIndex++;
        spawnCosmeticSlash(-35.0F + this.frostHitIndex * 12.0F, 2.0F, SLASH_COLOR);
        for (LivingEntity victim : this.frostTargets) {
            if (victim != null && victim.isAlive()) {
                CompanionDuty.frostDamage(this, victim, MistletoeDamage.LUMEN_BLADE, FROST_DAMAGE);
                if (this.level() instanceof net.minecraft.server.level.ServerLevel server) {
                    boolean wasSilent = victim.isSilent();
                    victim.setSilent(true);
                    server.getServer().tell(new net.minecraft.server.TickTask(2, () -> victim.setSilent(wasSilent)));
                    server.playSound(null, victim.getX(), victim.getY(0.8D), victim.getZ(),
                            SoundEvents.PLAYER_HURT_FREEZE, net.minecraft.sounds.SoundSource.NEUTRAL, 0.7F, 1.0F);
                    server.sendParticles(new net.minecraft.core.particles.BlockParticleOption(
                                    net.minecraft.core.particles.ParticleTypes.BLOCK,
                                    net.minecraft.world.level.block.Blocks.ICE.defaultBlockState()),
                            victim.getX(), victim.getY(0.9D), victim.getZ(), 6, 0.3D, 0.4D, 0.3D, 0.0D);
                }
            }
        }
        this.frostHitsLeft--;
        if (this.frostHitsLeft <= 0) {
            this.frostTargets = java.util.List.of();
        }
    }

    /** 水平扇形内的可攻击目标（友军/主人/超出守护半径的排除）。 */
    private java.util.List<LivingEntity> fanTargets(double range, double coneDegrees, Vec3 look) {
        double length = Math.hypot(look.x, look.z);
        Vec3 flat = length < 1.0E-4D
                ? new Vec3(0.0D, 0.0D, 1.0D)
                : new Vec3(look.x / length, 0.0D, look.z / length);
        double cosHalf = Math.cos(Math.toRadians(Math.max(1.0D, coneDegrees) * 0.5D));
        return this.level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(range), entity -> {
            if (entity == this || !shouldGuardTarget(entity)) {
                return false;
            }
            Vec3 to = new Vec3(entity.getX() - getX(), 0.0D, entity.getZ() - getZ());
            double distance = to.length();
            return distance <= range
                    && (distance < 1.0E-4D || flat.dot(to.scale(1.0D / distance)) >= cosHalf);
        });
    }

    private boolean shouldGuardTarget(LivingEntity target) {
        // 职责系统：驻守/村庄守卫/领队模式下先过 CompanionDuty.eligible（防区、袭击归属、队友排除）。
        if (!CompanionDuty.eligible(this, target)) {
            return false;
        }
        // 随从可以打 Boss（反之亦然），只排除自己、死亡、友军和超出守护半径的目标。
        if (target == null || target == this
                || !target.isAlive() || !canAttack(target) || isAlliedTo(target)) {
            return false;
        }
        LivingEntity owner = CompanionDuty.guardOwner(this);
        return owner == null || owner.distanceToSqr(target) <= OWNER_GUARD_RADIUS * OWNER_GUARD_RADIUS;
    }

    private void faceTarget(LivingEntity target) {
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        setYRot(yaw);
        setYHeadRot(yaw);
        this.yBodyRot = yaw;
    }

    private void spawnCosmeticSlash(float roll, float size, int color) {
        if (this.level().isClientSide()) {
            return;
        }
        var slash = mods.flammpfeil.slashblade.util.AttackManager.doSlash(this, roll, true, false, 0.0D);
        if (slash != null) {
            slash.setColor(color);
            slash.setBaseSize(size);
            slash.setLifetime(8);
            slash.setDamage(0.0D);
        }
    }

    private void refreshBlade() {
        if (this.level().isClientSide()) {
            return;
        }
        ResourceKey<SlashBladeDefinition> key = ResourceKey.create(
                SlashBladeDefinition.REGISTRY_KEY, LumenPhase.FIRST_SNOW.bladeId());
        ItemStack blade = this.level().registryAccess()
                .lookupOrThrow(SlashBladeDefinition.REGISTRY_KEY)
                .get(key)
                .map(holder -> holder.value().getBlade(this.level().registryAccess()))
                .orElse(ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlot.MAINHAND, blade);
    }

    // ── 交互：骑乘 / 坐下 / 背包 ────────────────────────────────────────

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // 未驯服：喂「任意食物」收服，每次 1/3 概率（参考原版狼喂骨头）
        if (!this.isTame()) {
            ItemStack held = player.getItemInHand(hand);
            if (this.isFood(held)) {
                if (!this.level().isClientSide()) {
                    this.usePlayerItem(player, hand, held);
                    if (this.getRandom().nextInt(3) == 0) {
                        this.tame(player);
                        this.setOrderedToSit(true);
                        this.setInSittingPose(true);
                        this.getNavigation().stop();
                        this.level().broadcastEntityEvent(this, (byte) 7);
                    } else {
                        this.level().broadcastEntityEvent(this, (byte) 6);
                    }
                }
                return InteractionResult.sidedSuccess(this.level().isClientSide());
            }

            return super.mobInteract(player, hand);
        }
        if (!this.isOwnedBy(player)) {
            return super.mobInteract(player, hand);
        }
        if (player.isShiftKeyDown()) {
            if (this.isOrderedToSit()) {
                this.setOrderedToSit(false);
                this.setInSittingPose(false);
            } else if (!this.isVehicle()) {
                this.setOrderedToSit(true);
                this.setInSittingPose(true);
                this.getNavigation().stop();
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        if (!player.getItemInHand(hand).isEmpty()) {
            if (!this.level().isClientSide()) {
                this.openInventory(player);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        if (this.isOrderedToSit()) {
            return InteractionResult.PASS;
        }
        if (!this.level().isClientSide()) {
            player.startRiding(this);
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    private void openInventory(Player player) {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, playerInv, p) -> new dev.xingguang.terminalblade.menu.LumenCompanionMenu(id, playerInv, this),
                    this.getDisplayName()),
                    buf -> buf.writeVarInt(this.getId()));
        }
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return this.getFirstPassenger() instanceof Player player && this.isOwnedBy(player) ? player : null;
    }

    @Override
    public Vec3 getPassengerRidingPosition(Entity passenger) {
        return new Vec3(this.getX(), this.getY() + 1.25D, this.getZ());
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isAlive() && this.getControllingPassenger() instanceof Player player) {
            this.setYRot(player.getYRot());
            this.yRotO = this.getYRot();
            this.setXRot(player.getXRot() * 0.5F);
            this.setRot(this.getYRot(), this.getXRot());
            this.yBodyRot = this.getYRot();
            this.yHeadRot = this.yBodyRot;
            float strafe = player.xxa * 0.5F;
            float forward = player.zza;
            if (forward <= 0.0F) {
                forward *= 0.25F;
            }
            this.setSpeed((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED));
            super.travel(new Vec3(strafe, 0.0D, forward));
            return;
        }
        super.travel(travelVector);
    }

    // ── 敌我判定 ────────────────────────────────────────────────────────

    @Override
    public boolean isAlliedTo(Entity entity) {
        if (super.isAlliedTo(entity)) {
            return true;
        }
        if (this.isTame() && entity instanceof LumenCompanionEntity other) {
            return Objects.equals(this.getOwnerUUID(), other.getOwnerUUID());
        }
        return false;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (this.isTame() && target instanceof Player player
                && player.getUUID().equals(this.getOwnerUUID())) {
            return false;
        }
        return super.canAttack(target);
    }

    /**
     * 模拟全套保护 IV 的减伤，在护甲减免之后调用（由 LumenDamageHandler 的 LivingDamageEvent.Pre 触发）。
     *
     * <p>注意：不能覆写 {@code getDamageAfterMagicAbsorb} —— NeoForge 1.21.1 的
     * {@code actuallyHurt} 会丢弃它的返回值，覆写等于无效。</p>
     */
    public float adjustFinalDamage(net.minecraft.world.damagesource.DamageSource source, float damage) {
        if (damage <= 0.0F || source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
            return damage;
        }
        return damage * PROTECTION_FACTOR;
    }

    @Override
    public boolean isFood(ItemStack stack) {
        // 临时援军不可驯服
        if (CompanionDuty.relief(this)) {
            return false;
        }
        // 任意食物都能用来收服
        return stack.has(net.minecraft.core.component.DataComponents.FOOD);
    }

    /** 同类（随从/流明）之间不产生伤害：范围攻击蹭到同伴也不会结仇互殴。 */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        Entity src = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        if (src instanceof LumenCompanionEntity || src instanceof LumenSeishinEntity) {
            return true;
        }
        return super.isInvulnerableTo(source);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 临时援军对玩家伤害免疫（袭击者仍可伤害）
        if (CompanionDuty.relief(this) && source.getEntity() instanceof Player) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    public AgeableMob getBreedOffspring(net.minecraft.server.level.ServerLevel level, AgeableMob other) {
        return null;
    }

    // ── GeckoLib 动画 ───────────────────────────────────────────────────

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animatableCache;
    }

    @Override
    public double getTick(Object animatable) {
        return this.tickCount;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 3, this::mainAnim));
    }

    private PlayState mainAnim(AnimationState<LumenCompanionEntity> state) {
        String desired = this.desiredAnimation();
        state.getController().setAnimationSpeed(switch (getAction()) {
            case MELEE -> 3.0D;
            case SLASH_ART -> 2.0D;
            case RUSH -> 2.0D;
            default -> 1.0D;
        });
        int sequence = this.getAnimSeq();
        if (!desired.equals(this.lastAnimName) || sequence != this.lastAnimSeq) {
            this.lastAnimName = desired;
            this.lastAnimSeq = sequence;
            state.getController().forceAnimationReset();
            // RUSH（援军返航的连续迅冲斩）是持续动作，必须循环播放，否则几 tick 就断
            boolean looping = getAction() == LumenAction.NONE || getAction() == LumenAction.RUSH
                    || this.isInSittingPose();
            if (looping) {
                state.getController().setAnimation(RawAnimation.begin().thenLoop(desired));
            } else {
                state.getController().setAnimation(RawAnimation.begin().thenPlay(desired));
            }
        }
        return PlayState.CONTINUE;
    }

    private String desiredAnimation() {
        if (this.isInSittingPose()) {
            return "sit";
        }
        LumenAction action = getAction();
        if (action == LumenAction.MELEE) {
            return switch (Math.min(this.entityData.get(DATA_COMBO_INDEX), 4)) {
                case 1 -> "slashblade:combo_a1";
                case 2 -> "slashblade:combo_a2";
                case 3 -> "slashblade:combo_a3";
                default -> "slashblade:combo_a4";
            };
        }
        if (action == LumenAction.SLASH_ART || action == LumenAction.FROST) {
            return "slashblade:combo_c";
        }
        if (action == LumenAction.RUSH) {
            // 连续迅冲斩：用 rapid_slash 去掉 Root 位移的版本（位移由代码驱动，避免模型前冲又弹回/陷地）
            return "mistletoe:escort_rush";
        }
        if (this.isLanternOut()) {
            return "slashblade:lantern";
        }
        double dx = this.getX() - this.xo;
        double dz = this.getZ() - this.zo;
        double speedSq = dx * dx + dz * dz;
        if (speedSq > 0.15D) {
            return "run";
        }
        if (speedSq > 0.005D) {
            return "walk";
        }
        return "idle";
    }

    // ── 存读档 ──────────────────────────────────────────────────────────

    /** 死亡时把随从背包里的东西掉出来，避免随从一死、里面的物品永久消失。 */
    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide()) {
            for (int slot = 0; slot < this.inventory.getContainerSize(); slot++) {
                ItemStack stack = this.inventory.getItem(slot);
                if (!stack.isEmpty()) {
                    this.spawnAtLocation(stack);
                    this.inventory.setItem(slot, ItemStack.EMPTY);
                }
            }
        }
        super.die(source);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("LumenInventory", this.inventory.createTag(this.registryAccess()));
        tag.putLong("LumenSlashCooldown", this.slashCooldownUntil);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("LumenInventory")) {
            this.inventory.fromTag(tag.getList("LumenInventory", 10), this.registryAccess());
        }
        this.slashCooldownUntil = tag.getLong("LumenSlashCooldown");
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // ── 名字 ────────────────────────────────────────────────────────────

    /** 随机名字的翻译键：每次生成随机挑一个。 */
    private static final String[] NAME_KEYS = {
            "entity.mistletoe.lumen_companion.name_lumen",
            "entity.mistletoe.lumen_companion.name_star",
    };

    /** 生成时随机取一个显示名（流明 / 小星）。 */
    public void randomDisplayName() {
        if (this.level().isClientSide() || this.hasCustomName()) {
            return;
        }
        this.setCustomName(Component.translatable(
                NAME_KEYS[this.getRandom().nextInt(NAME_KEYS.length)]));
        // 名字只作为身份用于文本，头顶不显示名牌
        this.setCustomNameVisible(false);
    }

    @Override
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(
            net.minecraft.world.level.ServerLevelAccessor level,
            net.minecraft.world.DifficultyInstance difficulty,
            net.minecraft.world.entity.MobSpawnType spawnType,
            net.minecraft.world.entity.SpawnGroupData spawnGroupData) {
        // 走刷怪蛋 / 自然生成这条路也要有名字
        randomDisplayName();
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
    }

    /**
     * 头顶永远不显示名牌。
     * 覆盖这个方法而不是只依赖 setCustomNameVisible，避免被别的逻辑重新打开。
     */
    @Override
    public boolean shouldShowName() {
        return false;
    }

    /** 供死亡复活逻辑取物品栏引用。 */
    public net.minecraft.world.SimpleContainer getInventory() {
        return this.inventory;
    }

    /** 主人 UUID（复活时用于重新绑定）。 */
    public UUID ownerId() {
        return this.getOwnerUUID();
    }

    static {
        TerminalBlade.LOGGER.debug("[流明·星] 随从实体已加载");
    }
}
