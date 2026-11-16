package dev.xingguang.terminalblade.duty;

import java.util.ArrayList;
import java.util.List;

public final class DutyRules {
    private DutyRules() {}
    public static boolean inArea(double dx, double dy, double dz, int radius) {
        return Math.abs(dy) <= 8 && dx * dx + dz * dz <= (double)radius * radius;
    }

    public static int radius(int value) {
        return switch (value) { case 12, 24, 40 -> value; default -> 24; };
    }

    public enum Role { PERIMETER, CENTRAL, VILLAGERS, ESCORT }
    public record Post(Role role, int slot, int dx, int dz) {}

    public static List<Post> reliefPosts() {
        int[][] directions = {{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
        var posts = new ArrayList<Post>(14);
        for (int i=0; i<8; i++) posts.add(new Post(Role.PERIMETER,i,directions[i][0],directions[i][1]));
        for (var role : List.of(Role.CENTRAL,Role.VILLAGERS,Role.ESCORT))
            for (int i=0; i<2; i++) posts.add(new Post(role,i,0,0));
        return List.copyOf(posts);
    }

    // Direction marker only. Minecraft terrain placement must separately check reachability.
    public static int[] interceptOffset(int dx, int dz, int radius, int extra) {
        double d=Math.hypot(dx,dz);
        if (d==0) return new int[]{0,0};
        double limit=Math.min(d,radius + extra);
        return new int[]{(int)(dx * limit / d),(int)(dz * limit / d)};
    }
}
