package dev.xingguang.terminalblade.mixin;

import dev.xingguang.terminalblade.duty.RaidRelief;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import dev.xingguang.terminalblade.entity.LumenSeishinEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 索敌过滤：
 * <ul>
 *     <li>临时援军（含流明领队）只能被本场袭击的成员选为目标；</li>
 *     <li>流明阵营内部（随从↔随从、随从↔流明、流明↔随从）互不索敌 ——
 *         配合 {@code isInvulnerableTo} 的同类免疫，彻底杜绝范围攻击结仇后的互殴。</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityCanAttackMixin {
    @Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void mistletoe$blockFriendlyTarget(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (target == null) {
            return;
        }
        Object self = this;
        if (self instanceof LumenCompanionEntity || self instanceof LumenSeishinEntity) {
            if (target instanceof LumenCompanionEntity || target instanceof LumenSeishinEntity) {
                cir.setReturnValue(false);
                return;
            }
        }
        if (RaidRelief.marked(target)
                && !RaidRelief.canBeAttackedBy((LivingEntity) self, target)) {
            cir.setReturnValue(false);
        }
    }
}
