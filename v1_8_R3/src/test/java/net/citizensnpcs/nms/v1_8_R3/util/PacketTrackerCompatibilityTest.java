package net.citizensnpcs.nms.v1_8_R3.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEgg;
import org.bukkit.entity.Player;
import org.junit.Test;

import net.citizensnpcs.util.EntityPacketTracker;
import net.citizensnpcs.util.EntityPacketTracker.PacketAggregator;
import net.minecraft.server.v1_8_R3.EntityEgg;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.PacketPlayOutSpawnEntity;

public class PacketTrackerCompatibilityTest {
    @Test
    public void packetNpcTickDoesNotAcquireAnUnlinkedWorldChunkWatcher() throws Exception {
        try (NativeTestSupport server = new NativeTestSupport()) {
            EntityEgg entity = new EntityEgg(null);
            entity.world = server.world;
            EntityPlayer included = server.player();
            EntityPlayer excluded = server.player();
            server.watchOriginChunk(included, excluded);
            // Prove the excluded viewer really is visible to the world's native watcher map.
            assertTrue(server.world.getPlayerChunkMap().a(excluded, 0, 0));
            NMSImpl bridge = NativeTestSupport.allocate(NMSImpl.class);
            EntityPacketTracker tracker = bridge.createPacketTracker(
                    new CraftEgg((CraftServer) Bukkit.getServer(), entity), new PacketAggregator());

            tracker.link(included.getBukkitEntity());
            tracker.run();
            entity.locX = 5;
            entity.ticksLived++;
            tracker.run();

            assertEquals(1, server.packets(included).stream().filter(PacketPlayOutSpawnEntity.class::isInstance).count());
            assertTrue("A world watcher outside the explicit audience must receive no packets",
                    server.packets(excluded).isEmpty());
            List<Player> removed = new ArrayList<>();
            tracker.unlinkAll(removed::add);
            tracker.unlinkAll(removed::add);
            tracker.run();
            assertEquals(Collections.singletonList(included.getBukkitEntity()), removed);
            assertEquals(1, included.removeQueue.size());
            assertEquals(entity.getId(), included.removeQueue.dequeueInt());
            assertTrue(server.packets(excluded).isEmpty());
        }
    }
}
