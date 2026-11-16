package dev.xingguang.terminalblade.duty;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * 流明灯：右键举灯（短抬手 + 风铃 + 冷光）。在有效袭击附近举灯即可求援，
 * 距离/去重/整批投放均由 {@link RaidRelief} 处理。
 */
public final class LumenLampItem extends Item {
    public LumenLampItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            level.playSound(null, player.blockPosition(),
                    dev.xingguang.terminalblade.registry.ModSounds.LAMP_SIGNAL.get(),
                    SoundSource.PLAYERS, 1.0F, 1.0F);
            RaidRelief.flash(serverPlayer.serverLevel(), player.getX(), player.getY() + 1.3D,
                    player.getZ(), 8, 0.24D);
            player.getCooldowns().addCooldown(this, 30);
            RaidRelief.call(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public int getUseDuration(ItemStack stack, net.minecraft.world.entity.LivingEntity user) {
        return 12;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.TOOT_HORN;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.mistletoe.lumen_lamp.hint").withStyle(ChatFormatting.GRAY));
    }
}
