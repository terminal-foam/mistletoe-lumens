package dev.xingguang.terminalblade.mixin;

import dev.xingguang.terminalblade.duty.RaidRelief;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 临时援军（含流明领队）只能被本场袭击的成员选为目标。
 * 铁傀儡、玩家、拔刀剑的索敌如果走 {@code canAttack}，都会在这里被挡掉。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityCanAttackMixin {
    @Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void mistletoe$blockReliefTarget(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (target != null && RaidRelief.marked(target)
                && !RaidRelief.canBeAttackedBy((LivingEntity) (Object) this, target)) {
            cir.setReturnValue(false);
        }
    }
}
