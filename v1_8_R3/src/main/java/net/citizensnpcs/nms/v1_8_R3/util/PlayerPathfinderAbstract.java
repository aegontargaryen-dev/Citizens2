package net.citizensnpcs.nms.v1_8_R3.util;

import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.IBlockAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.v1_8_R3.MathHelper;
import net.minecraft.server.v1_8_R3.PathPoint;
import net.minecraft.server.v1_8_R3.PathfinderAbstract;

public abstract class PlayerPathfinderAbstract extends PathfinderAbstract {
    protected IBlockAccess a;
    protected Long2ObjectMap<PathPoint> b = new Long2ObjectOpenHashMap<>();
    protected int c;
    protected int d;
    protected int e;

    @Override
    public void a() {
    }

    @Override
    public abstract PathPoint a(Entity paramEntity);

    @Override
    public abstract PathPoint a(Entity paramEntity, double paramDouble1, double paramDouble2, double paramDouble3);

    @Override
    public void a(IBlockAccess paramIBlockAccess, Entity paramEntity) {
        this.a = paramIBlockAccess;
        this.b.clear();
        this.c = MathHelper.d(paramEntity.width + 1.0F);
        this.d = MathHelper.d(paramEntity.length + 1.0F);
        this.e = MathHelper.d(paramEntity.width + 1.0F);
    }

    @Override
    protected PathPoint a(int paramInt1, int paramInt2, int paramInt3) {
        long i = PathPoint.a(paramInt1, paramInt2, paramInt3);
        PathPoint localPathPoint = this.b.get(i);
        if (localPathPoint == null) {
            localPathPoint = new PathPoint(paramInt1, paramInt2, paramInt3);
            this.b.put(i, localPathPoint);
        }
        return localPathPoint;
    }

    @Override
    public abstract int a(PathPoint[] paramArrayOfPathPoint, Entity paramEntity, PathPoint paramPathPoint1,
            PathPoint paramPathPoint2, float paramFloat);
}
