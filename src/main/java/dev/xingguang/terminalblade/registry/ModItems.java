package dev.xingguang.terminalblade.registry;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.duty.LumenLampItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, TerminalBlade.MOD_ID);

    public static final DeferredHolder<Item, Item> LUMEN_SEISHIN_SPAWN_EGG = ITEMS.register("lumen_seishin_spawn_egg",
            () -> new SpawnEggItem(ModEntities.LUMEN_SEISHIN.get(), 0x9FD8FF, 0x2A3B66, new Item.Properties()));

    public static final DeferredHolder<Item, Item> LUMEN_COMPANION_SPAWN_EGG = ITEMS.register("lumen_companion_spawn_egg",
    () -> new SpawnEggItem(ModEntities.LUMEN_COMPANION.get(), 0xE0C4FF, 0x3A2A66, new Item.Properties()));

    public static final DeferredHolder<Item, LumenLampItem> LUMEN_LAMP = ITEMS.register("lumen_lamp",
            () -> new LumenLampItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    /**
     * 红宝石：流明之间的货币（当年 Mojang 计划过、最后改成绿宝石的那个红宝石）。
     * 沿用原版废弃贴图，实现上是普通物品，没有额外功能。
     */
    public static final DeferredHolder<Item, Item> RUBY = ITEMS.register("ruby",
            () -> new Item(new Item.Properties().rarity(Rarity.UNCOMMON)));

    private ModItems() {
    }
}
