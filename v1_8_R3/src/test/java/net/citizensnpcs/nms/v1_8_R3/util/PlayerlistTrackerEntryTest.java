package net.citizensnpcs.nms.v1_8_R3.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import net.citizensnpcs.api.event.NPCLinkToPlayerEvent;
import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.event.NPCUnlinkFromPlayerEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.nms.v1_8_R3.entity.EntityHumanNPC;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.minecraft.server.v1_8_R3.EntityEgg;
import net.minecraft.server.v1_8_R3.EntityHuman;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EntityTracker;
import net.minecraft.server.v1_8_R3.EntityTrackerEntry;
import net.minecraft.server.v1_8_R3.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_8_R3.PacketPlayOutSpawnEntity;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityVelocity;

public class PlayerlistTrackerEntryTest {
    private NativeTestSupport server;
    private TrackedEgg entity;
    private PlayerlistTrackerEntry entry;
    private boolean cancelSeen;
    private final List<Integer> packetCountsAtLink = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        server = new NativeTestSupport();
        server.listen(NPCSeenByPlayerEvent.getHandlerList(), event -> {
            ((NPCSeenByPlayerEvent) event).setCancelled(cancelSeen);
        });
        server.listen(NPCLinkToPlayerEvent.getHandlerList(), event -> {
            CraftPlayer player = (CraftPlayer) ((NPCLinkToPlayerEvent) event).getPlayer();
            packetCountsAtLink.add(server.packets(player.getHandle()).size());
        });
        server.listen(NPCUnlinkFromPlayerEvent.getHandlerList(), event -> { });
        entity = new TrackedEgg(server.npc);
        entity.attachedToPlayer = true;
        entry = new PlayerlistTrackerEntry(entity, 80, 64, 3, false);
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.close();
    }

    @Test
    public void nativeMapAndKeySetStayLiveAndRepeatedUpdatesOnlyLinkOnce() throws Exception {
        EntityPlayer viewer = server.player();
        Object map = entry.trackedPlayerMap;
        Object keySet = entry.trackedPlayers;

        entry.updatePlayer(viewer);
        entry.updatePlayer(viewer);

        assertEquals("Link must precede the spawn so skin listeners can prepare the player",
                Collections.singletonList(0), packetCountsAtLink);
        assertSame(map, entry.trackedPlayerMap);
        assertSame(keySet, entry.trackedPlayers);
        assertEquals(1, entry.trackedPlayerMap.size());
        assertEquals(1, entry.trackedPlayers.size());
        assertTrue(entry.trackedPlayerMap.getBoolean(viewer));
        assertTrue(entry.trackedPlayers.contains(viewer));
        assertEquals(1, server.count(NPCSeenByPlayerEvent.class));
        assertEquals(1, server.count(NPCLinkToPlayerEvent.class));
        assertEquals(1, server.packets(viewer).stream().filter(PacketPlayOutSpawnEntity.class::isInstance).count());
        assertTrue(viewer.cancelledRemovals.contains(entity.getId()));
        NPCLinkToPlayerEvent linked = (NPCLinkToPlayerEvent) server.events.get(1);
        assertSame(server.npc, linked.getNPC());
        assertSame(viewer.getBukkitEntity(), linked.getPlayer());
        assertEquals(Collections.singleton(viewer.getBukkitEntity()), PlayerlistTrackerEntry.getSeenBy(entry));
    }

    @Test
    public void seenCancellationBlocksNativeMembershipAndSpawnThenAllowsRetry() throws Exception {
        EntityPlayer viewer = server.player();
        cancelSeen = true;
        entry.updatePlayer(viewer);

        assertTrue(entry.trackedPlayerMap.isEmpty());
        assertTrue(entry.trackedPlayers.isEmpty());
        assertTrue(server.packets(viewer).isEmpty());
        assertEquals(0, server.count(NPCLinkToPlayerEvent.class));

        cancelSeen = false;
        entry.updatePlayer(viewer);
        assertTrue(entry.trackedPlayers.contains(viewer));
        assertEquals(2, server.count(NPCSeenByPlayerEvent.class));
        assertEquals(1, server.count(NPCLinkToPlayerEvent.class));
    }

    @Test
    public void outOfRangeViewerDoesNotLinkAndMovingOutUnlinksOnce() throws Exception {
        EntityPlayer viewer = server.player();
        viewer.locX = 65;
        entry.updatePlayer(viewer);
        assertTrue(entry.trackedPlayers.isEmpty());
        assertEquals(0, server.count(NPCLinkToPlayerEvent.class));
        assertTrue(server.packets(viewer).isEmpty());

        viewer.locX = 64;
        entry.updatePlayer(viewer);
        assertTrue(entry.trackedPlayers.contains(viewer));
        viewer.locX = 65;
        entry.updatePlayer(viewer);
        entry.updatePlayer(viewer);
        entry.clear(viewer);

        assertTrue(entry.trackedPlayerMap.isEmpty());
        assertEquals(1, server.count(NPCLinkToPlayerEvent.class));
        assertEquals(1, server.count(NPCUnlinkFromPlayerEvent.class));
        assertEquals(1, viewer.removeQueue.size());
        assertEquals(entity.getId(), viewer.removeQueue.dequeueInt());
    }

    @Test
    public void clearAndBulkClearRemoveBothNativeViewsAndDoNotDoubleNotify() throws Exception {
        EntityPlayer first = server.player();
        EntityPlayer second = server.player();
        entry.updatePlayer(first);
        entry.updatePlayer(second);
        entry.clear(first);
        entry.clear(first);

        assertFalse(entry.trackedPlayers.contains(first));
        assertFalse(entry.trackedPlayerMap.containsKey(first));
        assertTrue(entry.trackedPlayers.contains(second));
        assertTrue(entry.trackedPlayerMap.containsKey(second));
        entry.a();
        entry.a();

        assertTrue(entry.trackedPlayers.isEmpty());
        assertTrue(entry.trackedPlayerMap.isEmpty());
        assertEquals(2, server.count(NPCUnlinkFromPlayerEvent.class));
        assertEquals(1, first.removeQueue.size());
        assertEquals(1, second.removeQueue.size());
        assertEquals(entity.getId(), first.removeQueue.dequeueInt());
        assertEquals(entity.getId(), second.removeQueue.dequeueInt());
        assertTrue(PlayerlistTrackerEntry.getSeenBy(entry).isEmpty());
    }

    @Test
    public void synchronizedFalseValuedViewersStillUnlinkThroughClearAndBulkClear() throws Exception {
        EntityPlayer first = server.player();
        EntityPlayer second = server.player();
        List<EntityHuman> audience = Arrays.<EntityHuman>asList(first, second);
        entry.track(null, audience);
        entity.locX = 1;
        entity.ai = true;
        entity.ticksLived++;
        entry.track(null, audience);
        assertTrue(entry.trackedPlayerMap.containsKey(first));
        assertTrue(entry.trackedPlayerMap.containsKey(second));
        assertFalse("Native relative-movement sync should consume the initial absolute-sync flag",
                entry.trackedPlayerMap.getBoolean(first));
        assertFalse(entry.trackedPlayerMap.getBoolean(second));

        entry.clear(first);
        entry.a();

        assertTrue(entry.trackedPlayers.isEmpty());
        assertTrue(entry.trackedPlayerMap.isEmpty());
        assertEquals(2, server.count(NPCUnlinkFromPlayerEvent.class));
        assertEquals(1, first.removeQueue.size());
        assertEquals(1, second.removeQueue.size());
    }

    @Test
    public void explicitAudienceIsHonoredWhenChunkWatchersAreUnavailable() throws Exception {
        EntityPlayer included = server.player();
        EntityPlayer excluded = server.player();
        List<EntityHuman> audience = Arrays.<EntityHuman>asList(included);

        entry.track(null, audience);
        entity.ticksLived++;
        entry.track(null, audience);

        assertTrue(entry.trackedPlayers.contains(included));
        assertFalse(entry.trackedPlayers.contains(excluded));
        assertEquals(1, entry.trackedPlayers.size());
        assertEquals(1, server.count(NPCLinkToPlayerEvent.class));
        assertTrue(server.packets(excluded).isEmpty());
    }

    @Test
    public void copiedEntryPreservesRangeFrequencyAndVelocityConfiguration() throws Exception {
        EntityTrackerEntry source = new EntityTrackerEntry(entity, 112, 72, 5, true);
        PlayerlistTrackerEntry copy = new PlayerlistTrackerEntry(source);
        EntityPlayer viewer = server.player();
        copy.updatePlayer(viewer);

        assertSame(entity, copy.tracker);
        assertEquals(112, copy.trackingRange);
        assertEquals(72, copy.b);
        assertEquals(5, copy.c);
        assertEquals(1, server.packets(viewer).stream().filter(PacketPlayOutEntityVelocity.class::isInstance).count());
    }

    @Test
    public void trackingRangeMetadataControlsActualLinkBoundary() throws Exception {
        server.metadata.set(NPC.Metadata.TRACKING_RANGE, 12);
        EntityPlayer viewer = server.player();
        viewer.locX = 13;
        entry.updatePlayer(viewer);
        assertFalse(entry.trackedPlayers.contains(viewer));
        viewer.locX = 12;
        entry.updatePlayer(viewer);
        assertTrue(entry.trackedPlayers.contains(viewer));
        assertEquals(12, entry.b);
        assertEquals(1, server.count(NPCLinkToPlayerEvent.class));
    }

    @Test
    public void nativeViewDistanceRefreshCannotWidenConfiguredRangeForExistingViewers() throws Exception {
        server.metadata.set(NPC.Metadata.TRACKING_RANGE, 12);
        EntityPlayer viewer = server.player();
        viewer.locX = 12;
        entry.updatePlayer(viewer);
        assertTrue(entry.trackedPlayers.contains(viewer));

        EntityTracker tracker = server.world.getTracker();
        NativeTestSupport.set(EntityTracker.class, tracker, "c",
                new ObjectOpenHashSet<>(Collections.singleton(entry)));
        tracker.updateViewDistanceBlocks(64);
        assertEquals(64, entry.b);
        viewer.locX = 13;
        entry.updatePlayer(viewer);

        assertEquals(12, entry.b);
        assertFalse(entry.trackedPlayers.contains(viewer));
        assertEquals(1, server.count(NPCLinkToPlayerEvent.class));
        assertEquals(1, server.count(NPCUnlinkFromPlayerEvent.class));
    }

    @Test
    public void playerNpcsAreNotAcceptedAsNetworkViewers() throws Exception {
        EntityHumanNPC npcViewer = NativeTestSupport.allocate(EntityHumanNPC.class);
        entry.updatePlayer(npcViewer);
        assertTrue(entry.trackedPlayerMap.isEmpty());
        assertTrue(entry.trackedPlayers.isEmpty());
        assertTrue(server.events.isEmpty());
    }

    @Test
    public void nativeHideShowUnlinksAndRelinksWithoutDuplicateSpawns() throws Exception {
        TrackedPlayer npcPlayer = server.initializePlayer(NativeTestSupport.allocate(TrackedPlayer.class));
        npcPlayer.npc = server.npc;
        npcPlayer.attachedToPlayer = true;
        PlayerlistTrackerEntry playerEntry = new PlayerlistTrackerEntry(npcPlayer, 80, 64, 3, false);
        server.world.getTracker().trackedEntities.put(npcPlayer.getId(), playerEntry);
        EntityPlayer viewer = server.player();
        playerEntry.updatePlayer(viewer);

        viewer.getBukkitEntity().hidePlayer(npcPlayer.getBukkitEntity());
        playerEntry.updatePlayer(viewer);
        assertFalse(playerEntry.trackedPlayers.contains(viewer));
        assertEquals(1, server.count(NPCLinkToPlayerEvent.class));
        assertEquals(1, server.count(NPCUnlinkFromPlayerEvent.class));

        viewer.getBukkitEntity().showPlayer(npcPlayer.getBukkitEntity());
        viewer.getBukkitEntity().showPlayer(npcPlayer.getBukkitEntity());
        playerEntry.updatePlayer(viewer);
        assertTrue(playerEntry.trackedPlayers.contains(viewer));
        assertEquals(2, server.count(NPCLinkToPlayerEvent.class));
        assertEquals(1, server.count(NPCUnlinkFromPlayerEvent.class));
        assertEquals(2, server.packets(viewer).stream().filter(PacketPlayOutNamedEntitySpawn.class::isInstance).count());
    }

    private static final class TrackedEgg extends EntityEgg implements NPCHolder {
        private final NPC npc;

        TrackedEgg(NPC npc) {
            super(null);
            this.npc = npc;
        }

        @Override
        public NPC getNPC() {
            return npc;
        }
    }

    private static final class TrackedPlayer extends EntityPlayer implements NPCHolder {
        private NPC npc;

        private TrackedPlayer() {
            super(null, null, null, null);
        }

        @Override
        public NPC getNPC() {
            return npc;
        }
    }
}
