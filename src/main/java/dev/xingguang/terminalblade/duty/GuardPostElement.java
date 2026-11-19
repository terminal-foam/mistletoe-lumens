package dev.xingguang.terminalblade.duty;

import com.mojang.datafixers.util.Either;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;

/**
 * 只用于「附加到村庄」的哨所单件。
 *
 * <p>{@link SinglePoolElement} 的构造器是 protected，这里开一个公开子类，
 * 让代码可以直接构造哨所件（不需要把它放进任何模板池）。</p>
 */
public final class GuardPostElement extends SinglePoolElement {
    public GuardPostElement(ResourceLocation template, Holder<StructureProcessorList> processors) {
        super(Either.left(template), processors, StructureTemplatePool.Projection.RIGID, Optional.empty());
    }
}
