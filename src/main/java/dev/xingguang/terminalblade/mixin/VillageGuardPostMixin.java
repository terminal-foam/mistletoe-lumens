package dev.xingguang.terminalblade.mixin;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.duty.VillageGuardPosts;
import java.util.Optional;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在村庄生成完成后，把「流明哨所」作为附加件塞进村庄结构。
 *
 * <p>村庄拼图本身没有空位放 17–21 格纵深的哨所，所以不参与拼图，直接在
 * {@link JigsawStructure#findGenerationPoint} 返回前追加一个独立的
 * {@code PoolElementStructurePiece}——它仍然属于这个村庄的 StructureStart，
 * 会随村庄一起写入存档、一起生成。</p>
 */
@Mixin(JigsawStructure.class)
abstract class VillageGuardPostMixin {
    private static int CALLS = 0;

    @Inject(method = "findGenerationPoint", at = @At("HEAD"))
    private void mistletoe$countCalls(Structure.GenerationContext context,
                                      CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (++CALLS <= 3) {
            TerminalBlade.LOGGER.debug("[哨所] findGenerationPoint 第 {} 次调用", CALLS);
        }
    }

    @Inject(method = "findGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void mistletoe$appendGuardPost(Structure.GenerationContext context,
                                           CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        Optional<Structure.GenerationStub> result = cir.getReturnValue();
        if (result == null || result.isEmpty()) {
            return;
        }
        Structure.GenerationStub replaced = VillageGuardPosts.append(context, result.get());
        if (replaced != null) {
            cir.setReturnValue(Optional.of(replaced));
        }
    }
}
