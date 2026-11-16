package dev.xingguang.terminalblade.duty;

import static dev.xingguang.terminalblade.duty.DutyRules.Role;
import static dev.xingguang.terminalblade.duty.DutyRules.inArea;
import static dev.xingguang.terminalblade.duty.DutyRules.interceptOffset;
import static dev.xingguang.terminalblade.duty.DutyRules.radius;
import static dev.xingguang.terminalblade.duty.DutyRules.reliefPosts;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DutyRulesTest {

    @Test
    void areaInclusiveAndRound() {
        assertTrue(inArea(24, 8, 0, 24), "inclusive edge");
        assertFalse(inArea(24.001, 0, 0, 24), "reject horizontal overrun");
        assertFalse(inArea(0, -8.01, 0, 24), "reject other floors");
        assertFalse(inArea(20, 0, 20, 24), "circle not square");
    }

    @Test
    void radiusWhitelist() {
        for (int r : new int[]{12, 24, 40}) {
            assertEquals(r, radius(r));
        }
        assertEquals(24, radius(-40));
        assertEquals(24, radius(25));
    }

    @Test
    void reliefPostsAre8Plus2Plus2Plus2() {
        EnumMap<Role, Integer> counts = new EnumMap<>(Role.class);
        HashSet<String> sectors = new HashSet<>();
        for (var p : reliefPosts()) {
            counts.merge(p.role(), 1, Integer::sum);
            if (p.role() == Role.PERIMETER) {
                sectors.add(p.dx() + "," + p.dz());
            }
        }
        assertEquals(8, sectors.size());
        assertFalse(sectors.contains("0,0"));
        assertEquals(Map.of(Role.PERIMETER, 8, Role.CENTRAL, 2, Role.VILLAGERS, 2, Role.ESCORT, 2), counts);
    }

    @Test
    void interceptOffsetsAreClampedAndDirectional() {
        assertArrayEquals(new int[]{0, 0}, interceptOffset(0, 0, 24, 8));
        assertArrayEquals(new int[]{9, -4}, interceptOffset(9, -4, 24, 8));
        assertArrayEquals(new int[]{32, 0}, interceptOffset(90, 0, 24, 8));
        for (int dx = -90; dx <= 90; dx += 5) {
            for (int dz = -90; dz <= 90; dz += 5) {
                int[] p = interceptOffset(dx, dz, 24, 8);
                assertTrue(Math.hypot(p[0], p[1]) <= 32, "hard radial intercept cap");
                assertTrue(p[0] * dx + p[1] * dz >= 0, "same direction");
            }
        }
    }
}
