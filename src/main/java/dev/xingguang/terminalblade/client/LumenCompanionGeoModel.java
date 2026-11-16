package dev.xingguang.terminalblade.client;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 随从形态复用同一套 YSM 贴图 / 动画，但**必须独立一份 geo**：
 * GeckoLib 的 baked model 缓存（{@code GeckoLibCache.MODELS}）按贴图路径全局共享，
 * 两个实体类型返回同一个 geo 时会共用同一批 {@code GeoBone}，动画逐骨骼覆盖，
 * 于是随从坐下写的 {@code Root.position} / {@code Skirt} 会残留在 Boss 身上（反之亦然）。
 */
public class LumenCompanionGeoModel extends GeoModel<LumenCompanionEntity> {
    private static final ResourceLocation MODEL = TerminalBlade.id("geo/lumen_seishin_companion.geo.json");
    private static final ResourceLocation TEXTURE = TerminalBlade.id("textures/entity/lumen_seishin_ysm.png");
    /** 与 Boss 共用同一份动画文件（全部动画已并进这一份，不再用 fallback）。 */
    private static final ResourceLocation ANIMATION = TerminalBlade.id("animations/lumen_seishin.animation.json");

    @Override
    public ResourceLocation getModelResource(LumenCompanionEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(LumenCompanionEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(LumenCompanionEntity animatable) {
        return ANIMATION;
    }
}
