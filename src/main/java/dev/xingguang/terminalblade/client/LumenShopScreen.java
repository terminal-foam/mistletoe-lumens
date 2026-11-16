package dev.xingguang.terminalblade.client;

import dev.xingguang.terminalblade.menu.LumenShopMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;

/**
 * 流明商店的界面：<b>完全沿用原版交易界面</b>，只把标题那一行从
 * 「流明·星 - 新手」改成「流明·星 - Day N」。
 *
 * <p>原版 {@code MerchantScreen.renderLabels} 只画这一行标题（等级名来自
 * {@code merchant.level.N}，不能单独改），所以这里整行重画，位置/颜色照抄原版：
 * x = 49 + imageWidth/2 - 文字宽/2，y = 6，颜色 4210752。</p>
 */
public class LumenShopScreen extends MerchantScreen {
    private final LumenShopMenu shopMenu;

    /**
     * 参数声明成 {@link MerchantMenu}（实际传进来的是 {@link LumenShopMenu}）：
     * 原版屏幕的泛型绑死在 MerchantMenu，方法引用只能这么对上。
     */
    public LumenShopScreen(MerchantMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.shopMenu = (LumenShopMenu) menu;
    }

    @Override
    protected void init() {
        super.init();
        // 排查用：日志里出现这行 = 我们的屏幕确实被用上了（而不是退回原版 MerchantScreen）
        dev.xingguang.terminalblade.TerminalBlade.LOGGER.info(
                "[流明·星] 商店界面已打开（Day {}）", this.shopMenu.day());
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        Component label = Component.translatable("merchant.title", this.title,
                Component.literal("Day " + this.shopMenu.day()));
        int width = this.font.width(label);
        graphics.drawString(this.font, label, 49 + this.imageWidth / 2 - width / 2, 6, 4210752, false);
    }
}
