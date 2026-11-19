package dev.xingguang.terminalblade.mixin;

import dev.xingguang.terminalblade.duty.HallSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 只替换 {@code Raid.findRandomSpawnPos} 的选点结果：袭击中心在流明之家内时，
 * 把整波刷到大厅点；其余情况完全按原版。
 */
@Mixin(Raid.class)
abstract class RaidSpawnMixin {
    @Inject(method = "findRandomSpawnPos(II)Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
    private void mistletoe$hallSpawn(int attempt, int tries, CallbackInfoReturnable<BlockPos> cir) {
        BlockPos point = HallSpawn.override((Raid) (Object) this);
        if (point != null) {
            cir.setReturnValue(point);
        }
    }
}
