package dev.xingguang.terminalblade.world;

import dev.xingguang.terminalblade.TerminalBlade;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 把「流明星们的家」里告示牌/剧情书的 %player% 占位符替换成当前玩家名字。
 *
 * <p>只处理 {@code mistletoe:lumen_home} 结构包围盒内的方块：
 * 不会碰其它结构/野外的箱子，也就不会提前触发未开启战利品箱的 loot 生成。</p>
 *
 * <p>带 loot table 的容器一律跳过（未开启的战利品箱连 getItem 都不调用）。</p>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID)
public final class SignPersonalizer {
    private static final String PLACEHOLDER = "%player%";
    private static final ResourceKey<Structure> HOME_STRUCTURE =
            ResourceKey.create(Registries.STRUCTURE, TerminalBlade.id("lumen_home"));

    private SignPersonalizer() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % 20 != 0) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getOrThrow(HOME_STRUCTURE);
        StructureStart start = level.structureManager().getStructureWithPieceAt(center, structure);
        if (start == null || !start.isValid()) {
            return;
        }
        BoundingBox box = start.getBoundingBox();
        BlockPos min = center.offset(-10, -6, -10);
        BlockPos max = center.offset(10, 6, 10);
        String name = player.getName().getString();

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!box.isInside(pos)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof SignBlockEntity sign) {
                personalizeSign(sign, name);
            } else if (blockEntity instanceof Container container) {
                // 未开启的战利品箱：读槽位会触发 loot 生成，直接跳过。
                if (blockEntity instanceof RandomizableContainerBlockEntity loot && loot.getLootTable() != null) {
                    continue;
                }
                personalizeContainers(container, name);
            }
        }
    }

    private static void personalizeSign(SignBlockEntity sign, String name) {
        SignText text = sign.getFrontText();
        boolean changed = false;
        for (int line = 0; line < 4; line++) {
            String raw = text.getMessage(line, false).getString();
            if (raw.contains(PLACEHOLDER)) {
                text = text.setMessage(line, Component.literal(raw.replace(PLACEHOLDER, name)));
                changed = true;
            }
        }
        if (changed) {
            sign.setText(text, true);
            sign.setChanged();
        }
    }

    private static void personalizeContainers(Container container, String name) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (content == null) {
                continue;
            }
            if (content.getPages(false).noneMatch(page -> page.contains(PLACEHOLDER))) {
                continue;
            }
            List<Filterable<String>> pages = content.getPages(false)
                    .map(page -> Filterable.passThrough(page.replace(PLACEHOLDER, name)))
                    .toList();
            stack.set(DataComponents.WRITABLE_BOOK_CONTENT, content.withReplacedPages(pages));
            container.setItem(slot, stack);
            container.setChanged();
        }
    }
}
