/*
 * Super SA 蓄力「槲寄生沿鞘身螺旋缠绕生长」—— 客户端渲染。
 *
 * 绘制放在 RenderOverrideEvent 内（沿用宿主给的 PoseStack，位置天然正确），
 * 但 RenderType 改成自己的 vanilla 类型：
 *   body → RenderType.entityCutoutNoCull(texture)
 *   glow → RenderType.entityTranslucentEmissive(texture)
 * 不再使用 slashblade 的自定义混合类型与 getSlashBladeBlendLuminous，
 * 避免 Iris/Veil 对自定义 RenderType 的批处理不兼容（Not building!）。
 *
 * 失败只跳过当前帧，每 tick 重置重试；日志节流。
 */
package dev.xingguang.terminalblade.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.blade.MistletoeBlade;
import mods.flammpfeil.slashblade.capability.inputstate.CapabilityInputState;
import mods.flammpfeil.slashblade.capability.inputstate.IInputState;
import mods.flammpfeil.slashblade.client.renderer.model.obj.WavefrontObject;
import mods.flammpfeil.slashblade.event.client.RenderOverrideEvent;
import mods.flammpfeil.slashblade.util.InputCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = TerminalBlade.MOD_ID, value = Dist.CLIENT)
public final class MistletoeGrowthRenderer {

    /** 与 make_growth.py 的常量必须一致。 */
    private static final int SEGMENTS = 32;
    private static final float SEG_LEN = 8.4375f;
    private static final float X0 = -16.0f;
    /** SuperSlashArts 的 chargeSuperSA 定在 pressTime + 20L。 */
    private static final float CHARGE_TICKS = 20.0f;
    private static final int WHITE = 0xFFFFFFFF;

    /** 每 tick 重置，失败只跳过当前帧，日志节流。 */
    private static boolean renderFailed;
    private static long lastFailLogTick = Long.MIN_VALUE;
    /** RenderType 必须复用同一个实例，否则 BufferSource 每次拿到新 key 永远不会被 flush。 */
    private static RenderType cachedBodyType;
    private static RenderType cachedGlowType;
    private static ResourceLocation cachedTex;
    private static boolean loggedOnce;
    /** sheath 每帧会被渲染两次（主手 + 背鞘），只画第一次，避免重复叠加闪烁。 */
    private static boolean drawnThisFrame;

    private static final String[] BODY_GROUPS = new String[SEGMENTS];
    private static final String[] GLOW_GROUPS = new String[SEGMENTS];

    static {
        for (int i = 0; i < SEGMENTS; i++) {
            BODY_GROUPS[i] = String.format("grow_%02d", i);
            GLOW_GROUPS[i] = String.format("grow_lum_%02d", i);
        }
    }

    private static float progress = 0.0f;

    private MistletoeGrowthRenderer() {
    }

    public static void setProgress(float p) {
        progress = Math.max(0.0f, Math.min(1.0f, p));
    }

    public static float getProgress() {
        return progress;
    }

    // ------------------------------------------------------------ 进度：零网络包
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        renderFailed = false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            progress = 0.0f;
            return;
        }
        ItemStack stack = player.getMainHandItem();
        IInputState input = player.getData(CapabilityInputState.INPUT_STATE.get());

        boolean charging = MistletoeBlade.isMistletoe(stack)
                && !MistletoeBlade.isDawn(stack)
                && !MistletoeBlade.isBrokenOrSealed(stack)
                && input != null
                && input.getCommands().contains(InputCommand.SPRINT)
                && (!dev.xingguang.terminalblade.TerminalBladeConfig.superRequiresGround() || player.onGround());

        if (!charging) {
            progress = 0.0f;
            return;
        }
        long press = input.getLastPressTime(InputCommand.SPRINT);
        if (press < 0L) {
            progress = 0.0f;
            return;
        }
        long now = player.level().getGameTime();
        long elapsed = now - press;
        if (elapsed < 0L || elapsed >= (long) CHARGE_TICKS) {
            progress = 0.0f;
            return;
        }
        progress = Math.max(0.0f, Math.min(1.0f, elapsed / CHARGE_TICKS));
    }

    // ------------------------------------------------ 在事件内绘制（位置正确）
    @SubscribeEvent
    public static void onRenderOverride(RenderOverrideEvent event) {
        if (progress <= 0.0f || renderFailed) {
            return;
        }
        if (!"sheath".equals(event.getOriginalTarget())) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || event.getStack() != player.getMainHandItem()) {
            return;
        }
        if (!MistletoeBlade.isMistletoe(event.getStack()) || MistletoeBlade.isDawn(event.getStack())) {
            return;
        }
        if (drawnThisFrame) {
            return;
        }

        WavefrontObject model = event.getModel();
        PoseStack pose = event.getPoseStack();
        ResourceLocation tex = event.getTexture();
        int light = event.getPackedLightIn();

        try {
            // 不用宿主自定义 RenderType，改用 vanilla 类型，Iris 只拦截它认识的那批。
            // 注意：RenderType 必须缓存；每帧 new 会拿到新的 key，宿主永远不会 endBatch → 不显示。
            if (cachedTex != tex || cachedBodyType == null || cachedGlowType == null) {
                cachedTex = tex;
                cachedBodyType = RenderType.entityCutoutNoCull(tex);
                cachedGlowType = RenderType.entityTranslucentEmissive(tex);
            }
            VertexConsumer body = event.getBuffer().getBuffer(cachedBodyType);
            VertexConsumer glow = event.getBuffer().getBuffer(cachedGlowType);
            if (!loggedOnce) {
                loggedOnce = true;
                TerminalBlade.LOGGER.info("[槲寄生] 蓄力藤蔓开始绘制（buffer={}）", event.getBuffer().getClass().getName());
            }

            float grown = progress * SEGMENTS;
            int whole = (int) Math.floor(grown);
            float frac = grown - whole;

            for (int i = 0; i < Math.min(whole, SEGMENTS); i++) {
                drawSegment(model, pose, body, glow, light, i, 1.0f);
            }
            if (whole < SEGMENTS && frac > 0.001f) {
                drawSegment(model, pose, body, glow, light, whole, Math.max(0.02f, frac));
            }
            drawnThisFrame = true;
        } catch (RuntimeException e) {
            renderFailed = true;
            long now = Minecraft.getInstance().level == null ? 0L : Minecraft.getInstance().level.getGameTime();
            if (now - lastFailLogTick > 200L) {
                lastFailLogTick = now;
                TerminalBlade.LOGGER.warn("[槲寄生] 蓄力藤蔓渲染跳过：{}", e.toString());
            }
        }
    }

    /** 帧首重置“已画”标记：AFTER_SKY 在实体渲染之前。 */
    @SubscribeEvent
    public static void onRenderLevelEarly(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) {
            drawnThisFrame = false;
        }
    }

    /** 实体层全部画完后统一提交，避免在宿主 pass 中间 endBatch 造成状态抖动/闪烁。 */
    @SubscribeEvent
    public static void onRenderLevelLate(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        if (cachedBodyType == null || cachedGlowType == null) {
            return;
        }
        var source = Minecraft.getInstance().renderBuffers().bufferSource();
        source.endBatch(cachedBodyType);
        source.endBatch(cachedGlowType);
    }

    private static void drawSegment(WavefrontObject model, PoseStack pose,
                                    VertexConsumer solidVb, VertexConsumer glowVb,
                                    int light, int i, float along) {
        String body = BODY_GROUPS[i];
        String glow = GLOW_GROUPS[i];
        if (along >= 0.999f) {
            model.tessellateOnly(solidVb, pose, light, WHITE, body);
            model.tessellateOnly(glowVb, pose, light, WHITE, glow);
            return;
        }
        float pivot = X0 - SEG_LEN * i;
        pose.pushPose();
        pose.translate(pivot, 0.0f, 0.0f);
        pose.scale(along, 1.0f, 1.0f);
        pose.translate(-pivot, 0.0f, 0.0f);
        model.tessellateOnly(solidVb, pose, light, WHITE, body);
        model.tessellateOnly(glowVb, pose, light, WHITE, glow);
        pose.popPose();
    }
}
