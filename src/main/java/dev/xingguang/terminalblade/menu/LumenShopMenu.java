package dev.xingguang.terminalblade.menu;

import dev.xingguang.terminalblade.registry.ModMenus;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;

/**
 * 流明商店的菜单：直接用原版 {@link MerchantMenu} 的那套逻辑（槽位、库存、成交判定都不重写），
 * 只多带一个「现在是 Day 几」给客户端画标题。
 *
 * <p>原版交易界面的标题会拼成 `<名字> - <村民等级名>`（新手/学徒/…），那个等级名来自原版语言键
 * {@code merchant.level.N}，没法只对本界面改。所以这里用自己的菜单类型 + 场景
 * （{@code LumenShopScreen}），把标题那一段改成 {@code Day N}。</p>
 *
 * <p><b>关键点</b>：{@code MerchantMenu} 的构造器把类型写死成 {@code MenuType.MERCHANT}，
 * 所以必须覆写 {@link #getType()} 返回我们自己的类型——否则 {@code openMenu} 发出去的
 * 屏幕包仍然是 MERCHANT，客户端会建原版 {@code MerchantScreen}，我们的屏幕永远不会被用上。</p>
 */
public class LumenShopMenu extends MerchantMenu {
    private final int day;

    /** 服务端：真正带商人实体。 */
    public LumenShopMenu(int containerId, Inventory playerInventory, Merchant merchant, int day) {
        super(containerId, playerInventory, merchant);
        this.day = day;
    }

    /** 客户端：只从网络包里读 Day，商人实体由原版的 offers 包另行同步。 */
    public LumenShopMenu(int containerId, Inventory playerInventory, int day) {
        super(containerId, playerInventory);
        this.day = day;
    }

    @Override
    public MenuType<?> getType() {
        return ModMenus.LUMEN_SHOP.get();
    }

    public int day() {
        return this.day;
    }
}
