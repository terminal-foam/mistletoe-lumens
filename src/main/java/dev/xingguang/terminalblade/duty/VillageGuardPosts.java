package dev.xingguang.terminalblade.duty;

import com.mojang.datafixers.util.Either;
import dev.xingguang.terminalblade.TerminalBlade;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * 把「流明哨所」附加到村庄结构里。
 *
 * <p>为什么不用村庄房屋池：拼图放置要求新件的包围盒不与街道/邻屋重叠，而哨所纵深 17–21 格，
 * 远超村庄房屋槽位（原版最深的房屋也只有 17 格，多数 11–13），所以作为池条目永远被跳过。</p>
 *
 * <p>这里改为在<b>村庄自己的生成结果</b>上直接追加一个 {@link PoolElementStructurePiece}：
 * 位置在村庄包围盒外侧、门口朝村内，从而绕开空间检查，也不受房屋池覆盖带来的兼容风险。</p>
 */
public final class VillageGuardPosts {
    private static final String[] BIOMES = {"plains", "snowy", "desert", "savanna", "taiga"};
    /** 模板里大门 jigsaw 的局部坐标（结构原点为 (0,0,0)）。 */
    private static final int ENTRANCE_X = 4;
    private static final int ENTRANCE_Z = 0;
    private static final int MARGIN = 3;

    private VillageGuardPosts() {
    }

    /**
     * 尝试给村庄结构追加哨所。
     *
     * @return 替换后的 stub（带新件）；不是村庄、或四个方向都放不下时返回 null
     */
    public static Structure.GenerationStub append(Structure.GenerationContext context,
                                                  Structure.GenerationStub stub) {
        StructurePiecesBuilder builder = stub.getPiecesBuilder();
        BoundingBox village = builder.getBoundingBox();
        if (village == null) {
            return null;
        }
        StructurePiece sample = builder.findCollisionPiece(village);
        if (!(sample instanceof PoolElementStructurePiece elementPiece)) {
            return null;
        }
        String biome = biomeOf(StructurePools.location(elementPiece.getElement()));
        if (biome == null) {
            return null;
        }
        int midX = (village.minX() + village.maxX()) / 2;
        int midZ = (village.minZ() + village.maxZ()) / 2;
        // 东 / 西 / 南 / 北 四个方向，入口在村外 MARGIN 格，门口朝村内
        int[][] sides = {
                {village.maxX() + MARGIN, midZ, 0}, // 东：门朝西
                {village.minX() - MARGIN, midZ, 1}, // 西：门朝东
                {midX, village.maxZ() + MARGIN, 2}, // 南：门朝北
                {midX, village.minZ() - MARGIN, 3}  // 北：门朝南
        };
        for (int[] side : sides) {
            BlockPos entrance = surface(context, side[0], side[1]);
            if (entrance == null) {
                continue;
            }
            Rotation rotation = facingRotation(side[2]);
            BlockPos rotated = StructureTemplate.transform(
                    new BlockPos(ENTRANCE_X, 0, ENTRANCE_Z), Mirror.NONE, rotation, BlockPos.ZERO);
            BlockPos anchor = new BlockPos(
                    entrance.getX() - rotated.getX(),
                    entrance.getY(),
                    entrance.getZ() - rotated.getZ());
            GuardPostElement element = new GuardPostElement(
                    TerminalBlade.id("village/guard_post_" + biome), emptyProcessors(context));
            BoundingBox box = element.getBoundingBox(context.structureTemplateManager(), anchor, rotation);
            if (builder.findCollisionPiece(box) != null) {
                continue;
            }
            builder.addPiece(new PoolElementStructurePiece(
                    context.structureTemplateManager(), element, anchor,
                    element.getGroundLevelDelta(), rotation, box, LiquidSettings.APPLY_WATERLOGGING));
            TerminalBlade.LOGGER.info("[流明·星] 村庄附加哨所 {} 入口={} 旋转={}", biome, entrance, rotation);
            return new Structure.GenerationStub(stub.position(), Either.right(builder));
        }
        TerminalBlade.LOGGER.debug("[流明·星] 村庄四个方向都放不下哨所（包围盒={}）", village);
        return null;
    }

    /** 从结构池 id 里取群系（{@code minecraft:village/plains/...} → plains）；不是村庄返回 null。 */
    private static String biomeOf(String location) {
        if (location == null || !location.startsWith("minecraft:village/")) {
            return null;
        }
        String[] parts = location.split("/");
        if (parts.length < 3) {
            return null;
        }
        for (String biome : BIOMES) {
            if (biome.equals(parts[2])) {
                return biome;
            }
        }
        return null;
    }

    /** 在 (x, z) 取地表高度；水面或取不到返回 null。 */
    private static BlockPos surface(Structure.GenerationContext context, int x, int z) {
        try {
            int y = context.chunkGenerator().getFirstFreeHeight(x, z,
                    Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
            int floor = context.chunkGenerator().getFirstFreeHeight(x, z,
                    Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
            if (y - floor > 1) {
                return null; // 水面以下
            }
            return new BlockPos(x, y, z);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 门朝哪边：0 东（门朝西）、1 西（门朝东）、2 南（门朝北）、3 北（门朝南）。 */
    private static Rotation facingRotation(int side) {
        return switch (side) {
            case 0 -> Rotation.COUNTERCLOCKWISE_90; // 北 → 西
            case 1 -> Rotation.CLOCKWISE_90;        // 北 → 东
            case 2 -> Rotation.NONE;                // 北
            default -> Rotation.CLOCKWISE_180;      // 北 → 南
        };
    }

    private static Holder<StructureProcessorList> emptyProcessors(Structure.GenerationContext context) {
        return context.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST)
                .getOrThrow(ResourceKey.create(Registries.PROCESSOR_LIST,
                        ResourceLocation.withDefaultNamespace("empty")));
    }
}
