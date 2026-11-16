package dev.xingguang.terminalblade.duty;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * 村庄哨所的守卫锚点：完全隐形、无碰撞、不可破坏、不掉落。
 *
 * <p>它不是给玩家用的方块，只由结构 NBT（{@code mistletoe:village/guard_post_<biome>}）
 * 写进建筑内部。{@link VillageGuards} 通过自定义 POI 找到它，并在**同一格**生成守卫，
 * 所以守卫一定出现在建筑里面，而不是钟附近的地面上。</p>
 *
 * <p>无碰撞很关键：守卫的脚就站在这个方块所在的格子里。</p>
 */
public class GuardPostBlock extends Block {
    public GuardPostBlock() {
        super(Properties.of()
                .mapColor(MapColor.NONE)
                .sound(SoundType.STONE)
                // -1 = 不可破坏：防止误拆导致某座哨所永久失去守卫
                .strength(-1.0F)
                .noCollission()
                .noOcclusion()
                .noLootTable());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
