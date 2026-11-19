package dev.xingguang.terminalblade.client;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.blade.MistletoeBlade;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 给三把刀补上「描述」和「用法」两行提示。
 *
 * <p>SlashBlade 本体的工具提示只读 {@code slashblade.tooltip.*}，不会读我们写的
 * {@code item.mistletoe.<刀>.desc/.hint}；不接这一层的话，语言文件里那两行永远不会显示。</p>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID, value = Dist.CLIENT)
public final class BladeTooltips {
    private BladeTooltips() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        String key = MistletoeBlade.bladeTranslationKey(event.getItemStack());
        if (key == null) {
            return;
        }
        event.getToolTip().add(Component.translatable(key + ".desc").withStyle(ChatFormatting.GRAY));
        // 用法提示只给暮色（朝露/初雪只留描述）。
        if (MistletoeBlade.TRANSLATION_KEY.equals(key)) {
            event.getToolTip().add(Component.translatable(key + ".hint").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
