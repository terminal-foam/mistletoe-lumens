package dev.xingguang.terminalblade.duty;

import dev.xingguang.terminalblade.TerminalBlade;
import dev.xingguang.terminalblade.entity.LumenCompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 模组临时接管的铁门。
 *
 * <p>规则：</p>
 * <ul>
 *     <li>只接管「原来是关着的」铁门；玩家/红石本来就开着的门不碰；</li>
 *     <li>开门用 {@link DoorBlock#setOpen}（会放铁门音效、发 BLOCK_OPEN 游戏事件），
 *         上半格直接同步，避免两次音效；</li>
 *     <li>门按「标签」持有（例如 {@code raid:2}）：标签未释放前一直开着；
 *         全部标签释放、门口也没有实体后，按<b>那一刻</b>的 {@code POWERED} 收尾 ——
 *         有红石供电就保持开，没供电就关回去。玩家中途拨拉杆打开的门不会被强行关掉。</li>
 * </ul>
 */
public final class HomeDoors {
    private record Key(ResourceKey<Level> dim, long pos) {
        static Key of(ServerLevel level, BlockPos p) {
            return new Key(level.dimension(), p.asLong());
        }
    }

    private static final class Managed {
        final BlockPos lower;
        final Map<String, Long> holds = new LinkedHashMap<>();
        final Set<BlockPos> opened = new LinkedHashSet<>();

        Managed(BlockPos lower) {
            this.lower = lower;
        }
    }

    private static final Map<Key, Managed> DOORS = new HashMap<>();

    private HomeDoors() {
    }

    /**
     * 让某扇门为某个标签保持打开。
     *
     * @param tag      持有者标识，例如 {@code "raid:2"}；用 {@link #releaseTag} 释放
     * @param deadline 安全截止游戏刻，避免标签忘了释放导致门一直开着
     */
    public static void hold(ServerLevel level, BlockPos lower, String tag, long deadline) {
        if (lower == null || tag == null) {
            return;
        }
        BlockState st = level.getBlockState(lower);
        if (!(st.getBlock() instanceof DoorBlock) || !st.is(Blocks.IRON_DOOR)) {
            return;
        }
        if (st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return;
        }
        Managed managed = DOORS.get(Key.of(level, lower));
        if (managed != null) {
            // 这扇门已经由模组接管：续期即可，不看 OPEN（现在的 OPEN 可能就是我们打开的）。
            managed.holds.put(tag, deadline);
            openLeaves(level, managed);
            return;
        }
        if (st.getValue(DoorBlock.OPEN)) {
            // 红石或玩家已经打开，不接管（收尾也不碰它）。
            return;
        }
        Managed m = new Managed(lower.immutable());
        m.holds.put(tag, deadline);
        DOORS.put(Key.of(level, lower), m);
        openLeaves(level, m);
    }

    /** 释放某个标签的全部门；下一次 tick 会按当前供电状态收尾。 */
    public static void releaseTag(String tag) {
        Iterator<Map.Entry<Key, Managed>> it = DOORS.entrySet().iterator();
        while (it.hasNext()) {
            Managed m = it.next().getValue();
            m.holds.remove(tag);
        }
    }

    public static int managedCount(ServerLevel level) {
        int n = 0;
        for (Key k : DOORS.keySet()) {
            if (k.dim().equals(level.dimension())) {
                n++;
            }
        }
        return n;
    }

    /** 每 tick 调用：清理过期标签、必要时补开、条件满足后按当前红石状态收尾。 */
    public static void tick(ServerLevel level) {
        if (DOORS.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<Map.Entry<Key, Managed>> it = DOORS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Managed> e = it.next();
            if (!e.getKey().dim().equals(level.dimension())) {
                continue;
            }
            Managed m = e.getValue();
            m.holds.entrySet().removeIf(h -> now > h.getValue());
            if (!m.holds.isEmpty()) {
                openLeaves(level, m);
                continue;
            }
            if (doorwayHasEntity(level, m.lower)) {
                continue;
            }
            restore(level, m);
            it.remove();
        }
    }

    /** 立刻打开（把两半设成 OPEN=true）。 */
    private static void openLeaves(ServerLevel level, Managed m) {
        BlockState lowerState = level.getBlockState(m.lower);
        if (!(lowerState.getBlock() instanceof DoorBlock door) || !lowerState.is(Blocks.IRON_DOOR)
                || lowerState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            m.opened.clear();
            return;
        }
        if (!lowerState.getValue(DoorBlock.OPEN)) {
            door.setOpen(null, level, lowerState, m.lower, true);
        }
        m.opened.add(m.lower);
        BlockPos upper = m.lower.above();
        BlockState upState = level.getBlockState(upper);
        if (upState.is(Blocks.IRON_DOOR) && upState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            if (!upState.getValue(DoorBlock.OPEN)) {
                level.setBlock(upper, upState.setValue(DoorBlock.OPEN, true), 2);
            }
            m.opened.add(upper);
        }
    }

    /** 按收尾时刻的红石供电状态恢复；玩家中途用拉杆打开的门保持开着。 */
    private static void restore(ServerLevel level, Managed m) {
        for (BlockPos leaf : m.opened) {
            BlockState st = level.getBlockState(leaf);
            if (!st.is(Blocks.IRON_DOOR) || !(st.getBlock() instanceof DoorBlock)) {
                continue;
            }
            boolean open = st.getValue(DoorBlock.POWERED);
            if (st.getValue(DoorBlock.OPEN) != open) {
                level.setBlock(leaf, st.setValue(DoorBlock.OPEN, open), 2);
            }
        }
        m.opened.clear();
        if (m.holds.isEmpty()) {
            TerminalBlade.LOGGER.debug("[流明·星] 自动门收尾 {}", m.lower);
        }
    }

    private static boolean doorwayHasEntity(ServerLevel level, BlockPos lower) {
        AABB area = new AABB(lower.getX(), lower.getY(), lower.getZ(),
                lower.getX() + 1, lower.getY() + 2, lower.getZ() + 1).inflate(0.4);
        return !level.getEntities((Entity) null, area, e -> e instanceof LivingEntity le && le.isAlive()).isEmpty();
    }
}
