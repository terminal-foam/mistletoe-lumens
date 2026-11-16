package dev.xingguang.terminalblade.duty;

import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.raid.Raid;

public final class WaveLog {
    private static final WeakHashMap<Raid, Wave> RECENT = new WeakHashMap<>();
    public record Wave(int number, BlockPos pos) {}
    public static void record(Raid raid, BlockPos pos) { RECENT.put(raid,new Wave(raid.getGroupsSpawned(),pos.immutable())); }
    public static Wave last(Raid raid) { return RECENT.get(raid); }
    private WaveLog() {}
}
