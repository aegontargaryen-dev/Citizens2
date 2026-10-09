package net.citizensnpcs.nms.v1_8_R3.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityEgg;
import net.minecraft.server.v1_8_R3.PathPoint;

public class PlayerPathfinderAbstractTest {
    @Test
    public void xCoordinatesThatOnlyDifferAboveBit31KeepSeparateCachedNodes() {
        CacheProbe cache = new CacheProbe();
        PathPoint origin = cache.point(0, 64, 0);
        PathPoint east = cache.point(1, 64, 0);
        PathPoint west = cache.point(-1, 64, 0);

        // These are distinct blocks even though narrowing the native keys to int aliases them.
        assertEquals((int) PathPoint.a(0, 64, 0), (int) PathPoint.a(1, 64, 0));
        assertEquals((int) PathPoint.a(0, 64, 0), (int) PathPoint.a(-1, 64, 0));
        assertNotSame(origin, east);
        assertNotSame(origin, west);
        assertNotSame(east, west);
        assertCoordinates(origin, 0, 64, 0);
        assertCoordinates(east, 1, 64, 0);
        assertCoordinates(west, -1, 64, 0);
        assertSame(origin, cache.point(0, 64, 0));
        assertSame(east, cache.point(1, 64, 0));
        assertSame(west, cache.point(-1, 64, 0));
    }

    @Test
    public void largePositiveAndNegativeCoordinatesDoNotShareNodes() {
        CacheProbe cache = new CacheProbe();
        int[][] positions = { { -30000000, 0, -30000000 }, { 30000000, 255, 30000000 },
                { -30000000, 255, 30000000 }, { 30000000, 0, -30000000 }, { 0, -1, 0 }, { 0, 256, 0 } };
        PathPoint[] nodes = new PathPoint[positions.length];
        for (int i = 0; i < positions.length; i++) {
            int[] position = positions[i];
            nodes[i] = cache.point(position[0], position[1], position[2]);
            assertCoordinates(nodes[i], position[0], position[1], position[2]);
            for (int j = 0; j < i; j++) {
                assertNotSame(nodes[j], nodes[i]);
            }
        }
        for (int i = 0; i < positions.length; i++) {
            int[] position = positions[i];
            assertSame(nodes[i], cache.point(position[0], position[1], position[2]));
        }
    }

    @Test
    public void startingAnotherSearchDropsVisitedStateAndRecalculatesDimensions() {
        CacheProbe cache = new CacheProbe();
        PathPoint old = cache.point(12, 70, -4);
        old.i = true;
        EntityEgg entity = new EntityEgg(null);
        entity.width = 1.4F;
        entity.length = 2.3F;

        cache.a(null, entity);

        PathPoint fresh = cache.point(12, 70, -4);
        assertNotSame(old, fresh);
        assertEquals(false, fresh.i);
        assertEquals(2, cache.c);
        assertEquals(3, cache.d);
        assertEquals(2, cache.e);
        assertSame(fresh, cache.point(12, 70, -4));
    }

    private static void assertCoordinates(PathPoint point, int x, int y, int z) {
        assertEquals(x, point.a);
        assertEquals(y, point.b);
        assertEquals(z, point.c);
    }

    private static final class CacheProbe extends PlayerPathfinderAbstract {
        PathPoint point(int x, int y, int z) {
            return a(x, y, z);
        }

        @Override
        public PathPoint a(Entity entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PathPoint a(Entity entity, double x, double y, double z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int a(PathPoint[] points, Entity entity, PathPoint current, PathPoint target, float range) {
            throw new UnsupportedOperationException();
        }
    }
}
