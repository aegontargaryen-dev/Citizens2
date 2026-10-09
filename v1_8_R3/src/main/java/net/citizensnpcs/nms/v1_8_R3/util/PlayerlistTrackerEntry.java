package net.citizensnpcs.nms.v1_8_R3.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;

import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;

import net.citizensnpcs.api.event.NPCLinkToPlayerEvent;
import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.event.NPCUnlinkFromPlayerEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.nms.v1_8_R3.entity.EntityHumanNPC;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.citizensnpcs.util.NMS;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EntityTrackerEntry;

/** Uses native lifecycle methods so AIgot's map and its key-set stay coherent. */
public class PlayerlistTrackerEntry extends EntityTrackerEntry {
    public PlayerlistTrackerEntry(Entity entity, int range, int effectiveRange, int frequency, boolean velocity) {
        super(entity, range, effectiveRange, frequency, velocity);
        // Link must fire before the native spawn packet is built (skin setup).
        // Both native views must refer to the very same identity-based map.
        trackedPlayerMap = new Reference2BooleanOpenHashMap<EntityPlayer>() {
            @Override
            public boolean put(EntityPlayer player, boolean value) {
                boolean existed = containsKey(player);
                boolean previous = super.put(player, value);
                if (!existed && tracker instanceof NPCHolder) {
                    Bukkit.getPluginManager().callEvent(new NPCLinkToPlayerEvent(
                            ((NPCHolder) tracker).getNPC(), player.getBukkitEntity()));
                }
                return previous;
            }
        };
        trackedPlayers = trackedPlayerMap.keySet();
    }

    public PlayerlistTrackerEntry(EntityTrackerEntry entry) {
        this(entry.tracker, entry.trackingRange, entry.b, entry.c, requiresVelocity(entry));
    }

    @Override
    public void updatePlayer(EntityPlayer player) {
        if (player instanceof EntityHumanNPC)
            return;
        if (tracker instanceof NPCHolder) {
            NPC npc = ((NPCHolder) tracker).getNPC();
            if (!trackedPlayers.contains(player)) {
                NPCSeenByPlayerEvent event = new NPCSeenByPlayerEvent(npc, player.getBukkitEntity());
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled())
                    return;
            }
            // The native view-distance refresh can reset b even for current viewers.
            Integer range = npc.data().get(NPC.Metadata.TRACKING_RANGE);
            if (range != null) {
                b = range;
            }
        }
        super.updatePlayer(player);
    }

    @Override
    public void clear(EntityPlayer player) {
        boolean wasTracked = trackedPlayers.contains(player);
        super.clear(player);
        if (wasTracked && !trackedPlayers.contains(player)) {
            unlinked(player);
        }
    }

    @Override
    public void a() {
        // Dispatch through clear rather than bypassing unlink events in the native bulk clear.
        for (EntityPlayer player : new ArrayList<>(trackedPlayers)) {
            clear(player);
        }
    }

    private void unlinked(EntityPlayer player) {
        if (tracker instanceof NPCHolder) {
            Bukkit.getPluginManager().callEvent(new NPCUnlinkFromPlayerEvent(
                    ((NPCHolder) tracker).getNPC(), player.getBukkitEntity()));
        }
    }

    public static Set<org.bukkit.entity.Player> getSeenBy(EntityTrackerEntry entry) {
        return entry.trackedPlayers.stream().map(EntityPlayer::getBukkitEntity).collect(Collectors.toSet());
    }

    private static boolean requiresVelocity(EntityTrackerEntry entry) {
        try {
            return U.getBoolean(entry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read AIgot tracker velocity configuration", e);
        }
    }

    private static final Field U = NMS.getField(EntityTrackerEntry.class, "u");
}
