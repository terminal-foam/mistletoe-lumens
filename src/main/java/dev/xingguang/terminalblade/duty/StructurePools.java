package dev.xingguang.terminalblade.duty;

import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

/** 读取 {@link StructurePoolElement} 的模板 id（诊断/识别村庄用）。 */
public final class StructurePools {
    private StructurePools() {
    }

    public static String location(StructurePoolElement element) {
        if (!(element instanceof SinglePoolElement)) {
            return null;
        }
        try {
            var field = SinglePoolElement.class.getDeclaredField("template");
            field.setAccessible(true);
            Object value = field.get(element);
            if (value instanceof com.mojang.datafixers.util.Either<?, ?> either) {
                return either.left().map(Object::toString).orElse(null);
            }
        } catch (Throwable ignored) {
            // 反射失败就当读不到
        }
        return null;
    }
}
