package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import dev.xingguang.terminalblade.registry.ModBlocks;
import dev.xingguang.terminalblade.registry.ModEntities;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 村庄守卫的自动投放：**每座「流明哨所」建筑生成一位无主流明/小星守卫，生成在建筑内部**。
 *
 * <p>判定依据不再是钟（meeting POI），而是结构 NBT 里写死的隐形锚点方块
 * {@code mistletoe:guard_post}（见 {@link GuardPostBlock}）。因此一个村庄有几座哨所，
 * 就会出几个守卫；哨所数量变了，守卫数量跟着变。</p>
 *
 * <p><b>为什么不用 POI：</b>1.21.1 的 {@code PoiTypes.forState} 只查一张由原版
 * {@code PoiTypes.bootstrap} 填充的静态表（已核对字节码），NeoForge 21.1.248 没有为它加
 * 注册表回退，所以模组自注册的 POI 类型永远不会被 {@code PoiManager} 索引。这里改用
 * 区块调色板查找：先用 {@link LevelChunkSection#maybeHas} 按调色板快速筛掉不含锚点的
 * 区块，命中后才逐格定位，开销和原来查钟同一量级。</p>
 */
@EventBusSubscriber(modid = TerminalBlade.MOD_ID)
public final class VillageGuards extends SavedData {
    private static final String FILE_ID = "mistletoe_village_guards";
    private static final SavedData.Factory<VillageGuards> FACTORY =
            new SavedData.Factory<>(VillageGuards::new, VillageGuards::load, null);

    /** 旧的「按钟记录」用的是 {@code dim@pos}，加前缀区分，避免历史存档里的旧键干扰。 */
    private static final String KEY_PREFIX = "post@";

    /** 只处理玩家 64 格（= 4 个区块）内的哨所锚点。 */
    private static final int SCAN_CHUNK_RADIUS = 4;
    /** 守卫驻守半径。 */
    private static final int GUARD_RADIUS = 24;
    /** 已有守卫的判定半径（哨所很小，4 格足够）。 */
    private static final double OCCUPIED_RADIUS = 4.0D;

    private final Set<String> guarded = new HashSet<>();

    public static VillageGuards of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public static VillageGuards load(CompoundTag tag, HolderLookup.Provider provider) {
        VillageGuards data = new VillageGuards();
        CompoundTag list = tag.getCompound("Guarded");
        for (String key : list.getAllKeys()) {
            data.guarded.add(key);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag list = new CompoundTag();
        for (String key : this.guarded) {
            list.putBoolean(key, true);
        }
        tag.put("Guarded", list);
        return tag;
    }

    @SubscribeEvent
    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getGameTime() % 100 != 0) {
            return;
        }
        Block marker = ModBlocks.GUARD_POST.get();
        ServerChunkCache chunks = level.getChunkSource();
        for (ServerPlayer player : level.players()) {
            int cx = player.chunkPosition().x;
            int cz = player.chunkPosition().z;
            for (int dx = -SCAN_CHUNK_RADIUS; dx <= SCAN_CHUNK_RADIUS; dx++) {
                for (int dz = -SCAN_CHUNK_RADIUS; dz <= SCAN_CHUNK_RADIUS; dz++) {
                    // 只查已加载区块，绝不因为扫描而强制加载
                    LevelChunk chunk = chunks.getChunkNow(cx + dx, cz + dz);
                    if (chunk == null) {
                        continue;
                    }
                    for (BlockPos post : findMarkers(level, chunk, marker)) {
                        spawnGuard(level, post);
                    }
                }
            }
        }
    }

    /** 在一个区块里找所有锚点。按调色板先筛，命中才逐格扫。 */
    private static List<BlockPos> findMarkers(ServerLevel level, LevelChunk chunk, Block marker) {
        List<BlockPos> found = List.of();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!section.maybeHas(state -> state.is(marker))) {
                continue;
            }
            int baseX = chunk.getPos().getMinBlockX();
            int baseY = level.getSectionYFromSectionIndex(i) << 4;
            int baseZ = chunk.getPos().getMinBlockZ();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.is(marker)) {
                            if (found.isEmpty()) {
                                found = new ArrayList<>();
                            }
                            found.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
        return found;
    }

    /** 在一座哨所的锚点上投放守卫。锚点本身无碰撞，守卫的脚就站在锚点格里。 */
    static void spawnGuard(ServerLevel level, BlockPos post) {
        String key = KEY_PREFIX + level.dimension().location() + "@" + post.asLong();
        VillageGuards data = of(level);
        if (data.guarded.contains(key)) {
            return;
        }
        // 头顶必须有空间，否则先不投放，等结构补完再试
        if (!level.isEmptyBlock(post.above())) {
            return;
        }
        // 这座哨所已经有村庄守卫了（存档丢失 SavedData 时也不会重复投放）
        for (LumenCompanionEntity other : level.getEntitiesOfClass(LumenCompanionEntity.class,
                new AABB(post).inflate(OCCUPIED_RADIUS))) {
            if (!CompanionDuty.village(other)) {
                continue;
            }
            BlockPos owned = CompanionDuty.pos(CompanionDuty.data(other), "Post", level);
            if (post.equals(owned) || other.blockPosition().distManhattan(post) <= 2) {
                data.guarded.add(key);
                data.setDirty();
                return;
            }
        }
        LumenCompanionEntity mob = ModEntities.LUMEN_COMPANION.get().create(level);
        if (mob == null) {
            return;
        }
        mob.moveTo(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, 0.0F, 0.0F);
        mob.randomDisplayName();
        var duty = CompanionDuty.data(mob);
        duty.putBoolean("Village", true);
        // "Post" 记锚点本身：既是重复投放的判据，也是死后重生（VillageRespawns）的定位点
        CompanionDuty.putPos(duty, "Post", level, post);
        CompanionDuty.save(mob, duty);
        CompanionDuty.setGuard(mob, level, post, GUARD_RADIUS);
        mob.setHealth(LumenCompanionEntity.MAX_HEALTH);
        if (!level.noCollision(mob)) {
            return;
        }
        level.addFreshEntity(mob);
        data.guarded.add(key);
        data.setDirty();
        TerminalBlade.LOGGER.info("[流明·星] 哨所守卫已生成：{}", post);
    }

    private VillageGuards() {
    }
}
