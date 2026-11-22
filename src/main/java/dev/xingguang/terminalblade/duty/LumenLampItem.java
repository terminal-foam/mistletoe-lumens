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
 * 流明灯：右键举灯（短抬手 + 风铃 + 冷光）。
 *
 * <ul>
 *   <li>附近有有效袭击：求援（{@link RaidRelief}）+ 放出灯里收纳的随从；</li>
 *   <li>平时（或袭击中蹲下）：收起 / 放出所有处于跟随状态的随从（{@link CompanionStorage}）。</li>
 * </ul>
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

            boolean raiding = CompanionDuty.raid(serverPlayer.serverLevel(), player.blockPosition()) != null;
            if (raiding && !player.isShiftKeyDown()) {
                // 袭击中：两个功能同时生效——求援 + 放出灯里的随从。
                RaidRelief.call(serverPlayer);
                int released = CompanionStorage.release(serverPlayer);
                if (released > 0) {
                    message(serverPlayer, "released", released);
                }
            } else {
                toggle(serverPlayer);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private static void toggle(ServerPlayer player) {
        if (CompanionStorage.storedCountOf(player) > 0) {
            int released = CompanionStorage.release(player);
            message(player, released > 0 ? "released" : "release_failed", released);
            return;
        }
        int moved = CompanionStorage.stow(player);
        message(player, moved > 0 ? "stored" : "no_followers", moved);
    }

    private static void message(ServerPlayer player, String key, int count) {
        Component line = key.equals("no_followers") || key.equals("release_failed")
                ? Component.translatable("message.mistletoe.lumen_lamp." + key)
                : Component.translatable("message.mistletoe.lumen_lamp." + key, count);
        player.displayClientMessage(line, true);
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
