package dev.xingguang.terminalblade.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.xingguang.terminalblade.TerminalBladeConfig;
import dev.xingguang.terminalblade.combat.MistletoeFormulas;
import dev.xingguang.terminalblade.entity.LumenSeishinEntity;
import dev.xingguang.terminalblade.registry.ModEntities;
import mods.flammpfeil.slashblade.registry.slashblade.SlashBladeDefinition;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static dev.xingguang.terminalblade.blade.MistletoeBlade.DAWN_NAME;
import static dev.xingguang.terminalblade.blade.MistletoeBlade.FIRST_SNOW_NAME;
import static dev.xingguang.terminalblade.blade.MistletoeBlade.NAME;

@EventBusSubscriber(modid = dev.xingguang.terminalblade.TerminalBlade.MOD_ID)
public final class MistletoeCommands {
    private MistletoeCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var give = Commands.literal("give")
                .executes(ctx -> give(ctx.getSource().getPlayerOrException(), NAME, "commands.mistletoe.give"))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> give(EntityArgument.getPlayer(ctx, "player"), NAME, "commands.mistletoe.give")));
        var giveDawn = Commands.literal("dawn")
                .executes(ctx -> give(ctx.getSource().getPlayerOrException(), DAWN_NAME, "commands.mistletoe.give.dawn"))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> give(EntityArgument.getPlayer(ctx, "player"), DAWN_NAME, "commands.mistletoe.give.dawn")));
        var giveFirstSnow = Commands.literal("first_snow")
                .executes(ctx -> give(ctx.getSource().getPlayerOrException(), FIRST_SNOW_NAME, "commands.mistletoe.give.first_snow"))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> give(EntityArgument.getPlayer(ctx, "player"), FIRST_SNOW_NAME, "commands.mistletoe.give.first_snow")));
        var boss = Commands.literal("boss")
                .executes(ctx -> spawnBoss(ctx.getSource()));
        // 排查商店进度用：打印最近那只流明对自己的阶段/经验/等待状态
        var shop = Commands.literal("shop")
                .executes(ctx -> shopStatus(ctx.getSource()));
        var audit = Commands.literal("audit")                .then(Commands.argument("target_max", FloatArgumentType.floatArg(0.0F))
                        .then(Commands.argument("player_current", FloatArgumentType.floatArg(0.0F))
                                .then(Commands.argument("player_max", FloatArgumentType.floatArg(0.1F))
                                        .executes(ctx -> audit(
                                                ctx.getSource(),
                                                FloatArgumentType.getFloat(ctx, "target_max"),
                                                FloatArgumentType.getFloat(ctx, "player_current"),
                                                FloatArgumentType.getFloat(ctx, "player_max"))))));
        // 排查「家里的袭击」：结构定位、坐标反推、住户、门、三个大厅点
        var home = Commands.literal("home")
                .executes(ctx -> homeStatus(ctx.getSource()));
        event.getDispatcher().register(Commands.literal("mistletoe")
                .requires(source -> source.hasPermission(2))
                .then(give)
                .then(giveDawn)
                .then(giveFirstSnow)
                .then(boss)
                .then(shop)
                .then(audit)
                .then(home));
    }

    /** `/mistletoe shop`：打印当前玩家对最近一只流明的商店进度。 */
    private static int shopStatus(net.minecraft.commands.CommandSourceStack source) {        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("需要由玩家执行。"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        LumenSeishinEntity boss = level.getEntitiesOfClass(LumenSeishinEntity.class,
                        player.getBoundingBox().inflate(48.0D))
                .stream().findFirst().orElse(null);
        if (boss == null) {
            source.sendSuccess(() -> Component.literal("附近 48 格内没有流明·星。"), false);
            return 0;
        }
        String line = dev.xingguang.terminalblade.trade.LumenStanding.describe(
                player, boss.getUUID());
        source.sendSuccess(() -> Component.literal("§b[流明·星]§r " + line), false);
        return 1;
    }

    /** `/mistletoe home`：排查「家里的袭击」——结构定位、坐标反推、住户、门、大厅点。 */
    private static int homeStatus(net.minecraft.commands.CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        var home = dev.xingguang.terminalblade.duty.LumenHome.at(level, at);
        if (home == null) {
            source.sendFailure(Component.literal("当前位置不在 mistletoe:lumen_home 结构内。"));
            return 0;
        }
        var lines = new java.util.ArrayList<String>();
        lines.addAll(home.describe());
        var doors = dev.xingguang.terminalblade.duty.HomeDoors.managedCount(level);
        lines.add("模组接管的门：" + doors);
        var diag = dev.xingguang.terminalblade.duty.HomeRally.diagnose(level, home);
        if (!diag.isEmpty()) {
            lines.add("住户 → 房间门：");
            diag.forEach((name, door) -> lines.add("  " + name + " → " + door));
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal("§b[流明·星]§r " + line), false);
        }
        return 1;
    }

    private static int spawnBoss(net.minecraft.commands.CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        LumenSeishinEntity boss = ModEntities.LUMEN_SEISHIN.get().create(level);
        if (boss == null) {
            return 0;
        }
        Vec3 pos = source.getPosition();
        boss.moveTo(pos.x, pos.y, pos.z, source.getRotation().y, 0.0F);
        boss.setPersistenceRequired();
        level.addFreshEntity(boss);
        source.sendSuccess(() -> Component.translatable("commands.mistletoe.boss"), true);
        return 1;
    }

    private static int give(ServerPlayer player, net.minecraft.resources.ResourceLocation name, String messageKey) {
        ResourceKey<SlashBladeDefinition> key = ResourceKey.create(SlashBladeDefinition.REGISTRY_KEY, name);
        ItemStack blade = player.server.registryAccess()
                .lookupOrThrow(SlashBladeDefinition.REGISTRY_KEY)
                .getOrThrow(key)
                .value()
                .getBlade(player.registryAccess());
        if (DAWN_NAME.equals(name) && !blade.isEmpty()) {
            // 刀铭：未附魔仍是封刀，附魔后满足“印咒 + 赋铭”直接变妖。
            blade.set(DataComponents.CUSTOM_NAME, dev.xingguang.terminalblade.blade.MistletoeBlade.dawnName());
        }
        if (blade.isEmpty() || !player.addItem(blade)) {
            player.drop(blade, false);
        }
        player.displayClientMessage(Component.translatable(messageKey, player.getDisplayName()), false);
        return 1;
    }

    private static int audit(net.minecraft.commands.CommandSourceStack source, float targetMax, float playerCurrent, float playerMax) {
        float normal = MistletoeFormulas.normalDamage(
                TerminalBladeConfig.baseDamage(),
                TerminalBladeConfig.gapCoefficient(),
                targetMax,
                playerCurrent,
                TerminalBladeConfig.clampNegativeGap());
        boolean enhanced = MistletoeFormulas.isEnhancedSlashArt(targetMax, TerminalBladeConfig.slashArtThreshold());
        float cost = enhanced ? MistletoeFormulas.sacrificeCost(playerCurrent, TerminalBladeConfig.slashArtSacrificeRatio()) : 0.0F;
        float after = Math.max(0.0F, playerCurrent - cost);
        float sa = MistletoeFormulas.slashArtDamage(
                TerminalBladeConfig.baseDamage(),
                TerminalBladeConfig.gapCoefficient(),
                targetMax,
                enhanced ? after : playerCurrent,
                TerminalBladeConfig.clampNegativeGap(),
                TerminalBladeConfig.slashArtThreshold(),
                TerminalBladeConfig.slashArtMaxHealthBonus(),
                1.0F);
        MistletoeFormulas.SuperSacrifice superSacrifice = MistletoeFormulas.superSacrifice(
                playerCurrent, playerMax, TerminalBladeConfig.superSacrificeRatio());
        source.sendSuccess(() -> Component.translatable("commands.mistletoe.audit",
                normal, enhanced, cost, sa, superSacrifice.cost(), superSacrifice.lethal(),
                TerminalBladeConfig.superRadius()), false);
        return 1;
    }
}
