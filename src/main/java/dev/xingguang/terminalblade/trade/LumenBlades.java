package dev.xingguang.terminalblade.trade;

import dev.xingguang.terminalblade.blade.MistletoeBlade;
import mods.flammpfeil.slashblade.registry.slashblade.SlashBladeDefinition;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** 商店里要发「刀」时用的小工具：从 SlashBlade 定义注册表造一把成品刀。 */
final class LumenBlades {
    private LumenBlades() {
    }

    static ItemStack firstSnow(ServerLevel level) {
        return create(level, MistletoeBlade.FIRST_SNOW_NAME);
    }

    static ItemStack create(ServerLevel level, net.minecraft.resources.ResourceLocation name) {
        ResourceKey<SlashBladeDefinition> key = ResourceKey.create(SlashBladeDefinition.REGISTRY_KEY, name);
        return level.registryAccess()
                .lookupOrThrow(SlashBladeDefinition.REGISTRY_KEY)
                .getOrThrow(key)
                .value()
                .getBlade(level.registryAccess());
    }
}
