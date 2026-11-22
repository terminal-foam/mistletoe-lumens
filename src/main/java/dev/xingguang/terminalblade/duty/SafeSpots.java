package dev.xingguang.terminalblade.duty;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.MagmaBlock;

/** 找一个能站人的位置：脚/头两格是空气，脚下实心且不是岩浆块或篝火。 */
public final class SafeSpots {
    private SafeSpots() {
    }

    /** 从 {@code from} 向上找最多 12 格；找不到返回 null。 */
    public static BlockPos find(ServerLevel level, BlockPos from) {
        if (from == null) {
            return null;
        }
        for (int dy = 0; dy < 12; dy++) {
            BlockPos feet = from.above(dy);
            if (!level.isEmptyBlock(feet) || !level.isEmptyBlock(feet.above())) {
                continue;
            }
            BlockPos below = feet.below();
            var floor = level.getBlockState(below);
            if (floor.isFaceSturdy(level, below, Direction.UP)
                    && !(floor.getBlock() instanceof MagmaBlock)
                    && !(floor.getBlock() instanceof CampfireBlock)) {
                return feet;
            }
        }
        return null;
    }
}
