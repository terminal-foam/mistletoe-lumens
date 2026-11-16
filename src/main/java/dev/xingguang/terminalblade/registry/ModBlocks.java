package dev.xingguang.terminalblade.registry;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.duty.GuardPostBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(BuiltInRegistries.BLOCK, TerminalBlade.MOD_ID);

    /** 隐形守卫锚点，只由村庄哨所结构写入。 */
    public static final DeferredHolder<Block, Block> GUARD_POST =
            BLOCKS.register("guard_post", GuardPostBlock::new);

    private ModBlocks() {
    }
}
