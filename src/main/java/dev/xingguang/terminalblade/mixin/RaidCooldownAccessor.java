package dev.xingguang.terminalblade.mixin;

import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 只读波次冷却（{@code raidCooldownTicks}），用来算预警圈的时机。 */
@Mixin(Raid.class)
public interface RaidCooldownAccessor {
    @Accessor("raidCooldownTicks")
    int mistletoe$cooldownTicks();
}
