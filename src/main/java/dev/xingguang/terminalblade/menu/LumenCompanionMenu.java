package dev.xingguang.terminalblade.menu;

import dev.xingguang.terminalblade.duty.CompanionDuty;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import dev.xingguang.terminalblade.registry.ModMenus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 随从背包 + 职责指令页。
 *
 * <p>36 格背包保持原样；额外提供两个菜单按钮（id 0=跟随，id 1=驻守），
 * 通过 {@link AbstractContainerMenu#clickMenuButton(Player, int)} 在服务端执行，
 * 客户端用 {@code handleInventoryButtonClick} 发送。</p>
 */
public class LumenCompanionMenu extends AbstractContainerMenu {
    public static final int BUTTON_FOLLOW = 0;
    public static final int BUTTON_GARRISON = 1;

    private final int entityId;
    private final SimpleContainer container;

    /** 服务端构造：直接使用随从背包。 */
    public LumenCompanionMenu(int id, Inventory playerInv, LumenCompanionEntity entity) {
        super(ModMenus.COMPANION.get(), id);
        this.entityId = entity.getId();
        this.container = entity.getInventory();
        addSlots(playerInv);
    }

    /** 客户端构造：背包槽位由服务端同步，容器本身用空壳。 */
    public LumenCompanionMenu(int id, Inventory playerInv, int entityId) {
        super(ModMenus.COMPANION.get(), id);
        this.entityId = entityId;
        this.container = new SimpleContainer(36);
        addSlots(playerInv);
    }

    private void addSlots(Inventory playerInv) {
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(container, col + row * 9, 8 + col * 18, 18 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 103 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 161));
        }
    }

    public int getEntityId() {
        return this.entityId;
    }

    @Override
    public boolean stillValid(Player player) {
        if (!(player.level().getEntity(this.entityId) instanceof LumenCompanionEntity entity)) {
            return false;
        }
        return entity.isAlive() && entity.distanceToSqr(player) <= 8.0D * 8.0D;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player.level() instanceof ServerLevel level) || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (!(level.getEntity(this.entityId) instanceof LumenCompanionEntity entity)) {
            return false;
        }
        if (!entity.isOwnedBy(serverPlayer)) {
            return false;
        }
        if (id == BUTTON_FOLLOW) {
            CompanionDuty.follow(entity, level);
            return true;
        }
        if (id == BUTTON_GARRISON) {
            return CompanionDuty.garrisonFromUi(serverPlayer, entity);
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
