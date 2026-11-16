package dev.xingguang.terminalblade.client;

import dev.xingguang.terminalblade.menu.LumenCompanionMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 随从背包界面 + 跟随/驻守按钮。
 *
 * <p>背景复用原版大箱子的 generic_54.png；按钮放在标题行位置（y=4..18），
 * 不遮挡背包槽位。</p>
 */
public class LumenCompanionScreen extends AbstractContainerScreen<LumenCompanionMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    public LumenCompanionScreen(LumenCompanionMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, Component.empty());
        this.imageHeight = 186;
        this.inventoryLabelY = 94;
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(Component.literal("跟随"),
                        button -> this.minecraft.gameMode.handleInventoryButtonClick(
                                this.menu.containerId, LumenCompanionMenu.BUTTON_FOLLOW))
                .bounds(this.leftPos + 6, this.topPos + 4, 56, 14)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("驻守"),
                        button -> this.minecraft.gameMode.handleInventoryButtonClick(
                                this.menu.containerId, LumenCompanionMenu.BUTTON_GARRISON))
                .bounds(this.leftPos + 66, this.topPos + 4, 56, 14)
                .build());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 上方 4 行容器 + 下方玩家背包区（generic_54 的对应区域拼接）
        graphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, 90);
        graphics.blit(TEXTURE, this.leftPos, this.topPos + 90, 0, 126, this.imageWidth, 96);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题行留给按钮，只画玩家背包标题
        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY,
                4210752, false);
    }
}
