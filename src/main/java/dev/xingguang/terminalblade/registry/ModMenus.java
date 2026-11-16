package dev.xingguang.terminalblade.registry;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.menu.LumenCompanionMenu;
import dev.xingguang.terminalblade.menu.LumenShopMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, TerminalBlade.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<LumenCompanionMenu>> COMPANION =
            MENUS.register("companion", () -> IMenuTypeExtension.create(
                    (id, inv, buf) -> new LumenCompanionMenu(id, inv, buf.readVarInt())));

    /**
     * 流明商店：界面上要显示 Day N，所以用自己的菜单类型套自己的屏幕。
     *
     * <p>泛型刻意写成 {@code MenuType<MerchantMenu>}（实际实例是 {@link LumenShopMenu} 子类）：
     * 原版 {@code MerchantScreen} 绑死在 {@code MerchantMenu} 上，
     * 只有让菜单类型也声明成 {@code MerchantMenu} 才能注册自定义屏幕。</p>
     */
    public static final DeferredHolder<MenuType<?>, MenuType<MerchantMenu>> LUMEN_SHOP =
            MENUS.register("lumen_shop", () -> IMenuTypeExtension.<MerchantMenu>create(
                    (id, inv, buf) -> new LumenShopMenu(id, inv, buf.readVarInt())));

    private ModMenus() {
    }
}
