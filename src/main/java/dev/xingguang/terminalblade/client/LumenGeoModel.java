package dev.xingguang.terminalblade.client;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.entity.LumenSeishinEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 流明·星的 GeckoLib 模型。直接使用 YSM 导出的 Bedrock 资源（64 骨骼 + 拔刀剑动画）。
 */
public class LumenGeoModel extends GeoModel<LumenSeishinEntity> {
    private static final ResourceLocation MODEL = TerminalBlade.id("geo/lumen_seishin.geo.json");
    private static final ResourceLocation TEXTURE = TerminalBlade.id("textures/entity/lumen_seishin_ysm.png");
    /**
     * 全部动画（48 段 slashblade:* + idle/walk/run/sit/charge）。
     * 基础移动动作已并入本文件，不再依赖 {@code getAnimationResourceFallbacks}，
     * 这样 GeckoLib 4.6 等旧版本也不会缺待机/移动/坐下动画。
     */
    private static final ResourceLocation ANIMATION = TerminalBlade.id("animations/lumen_seishin.animation.json");

    @Override
    public ResourceLocation getModelResource(LumenSeishinEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(LumenSeishinEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(LumenSeishinEntity animatable) {
        return ANIMATION;
    }
}
