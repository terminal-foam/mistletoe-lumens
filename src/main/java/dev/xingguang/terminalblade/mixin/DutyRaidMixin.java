package dev.xingguang.terminalblade.mixin;

import dev.xingguang.terminalblade.duty.WaveLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Raid.class)
abstract class DutyRaidMixin {
    @Inject(method="spawnGroup(Lnet/minecraft/core/BlockPos;)V",at=@At("RETURN"))
    private void mistletoe$recordDutyWave(BlockPos pos,CallbackInfo ci) {
        WaveLog.record((Raid)(Object)this,pos);
    }
}
