package net.citizensnpcs.nms.v1_8_R3.util;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_8_R3.scoreboard.CraftScoreboardManager;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.SimplePluginManager;

import com.mojang.authlib.GameProfile;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.SimpleMetadataStore;
import net.minecraft.server.v1_8_R3.AttributeMapServer;
import net.minecraft.server.v1_8_R3.DataWatcher;
import net.minecraft.server.v1_8_R3.ChunkCoordIntPair;
import net.minecraft.server.v1_8_R3.DedicatedPlayerList;
import net.minecraft.server.v1_8_R3.DedicatedServer;
import net.minecraft.server.v1_8_R3.DispenserRegistry;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityHuman;
import net.minecraft.server.v1_8_R3.EntityLiving;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EntityTracker;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.Scoreboard;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PlayerChunkMap;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import net.minecraft.server.v1_8_R3.PlayerInteractManager;
import net.minecraft.server.v1_8_R3.PlayerInventory;
import net.minecraft.server.v1_8_R3.PlayerList;
import net.minecraft.server.v1_8_R3.WorldServer;

/**
 * Test infrastructure only: the tracker, player-removal queue, CraftPlayer visibility,
 * packets and event dispatch all execute the supplied server JAR's real classes.
 * Constructor-free allocation avoids starting a listener socket or generating a world.
 */
final class NativeTestSupport implements AutoCloseable {
    final List<Event> events = new ArrayList<>();
    final SimpleMetadataStore metadata = new SimpleMetadataStore();
    final NPC npc;
    final Plugin plugin;
    final WorldServer world;
    final SimplePluginManager plugins;
    private final Object oldBukkitServer;
    private final Object oldMinecraftServer;
    private final List<RegisteredListener> listeners = new ArrayList<>();
    private int nextId = 100;

    NativeTestSupport() throws Exception {
        DispenserRegistry.c();
        oldBukkitServer = field(Bukkit.class, "server").get(null);
        oldMinecraftServer = field(MinecraftServer.class, "l").get(null);
        DedicatedServer server = allocate(DedicatedServer.class);
        set(MinecraftServer.class, server, "primaryThread", Thread.currentThread());
        set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
        set(MinecraftServer.class, null, "l", server);

        CraftServer bukkit = allocate(CraftServer.class);
        set(CraftServer.class, bukkit, "console", server);
        bukkit.scoreboardManager = new CraftScoreboardManager(server, new Scoreboard());
        set(CraftServer.class, bukkit, "logger", Logger.getLogger("Citizens native tests"));
        plugins = new SimplePluginManager(bukkit, null);
        set(CraftServer.class, bukkit, "pluginManager", plugins);
        set(Bukkit.class, null, "server", bukkit);
        plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] { Plugin.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isEnabled": return true;
                        case "getName": return "CitizensNativeTests";
                        case "getDescription": return new PluginDescriptionFile("CitizensNativeTests", "1", "test.Main");
                        case "getLogger": return Logger.getLogger("Citizens native tests");
                        case "getServer": return bukkit;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "CitizensNativeTests";
                        default: throw new UnsupportedOperationException(method.toString());
                    }
                });
        npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] { NPC.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "data": return metadata;
                        case "getId": return 1;
                        case "getName": return "Tracked NPC";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "Tracked NPC";
                        default: throw new UnsupportedOperationException(method.toString());
                    }
                });
        DedicatedPlayerList playerList = allocate(DedicatedPlayerList.class);
        set(PlayerList.class, playerList, "r", 5);
        set(MinecraftServer.class, server, "v", playerList);
        world = allocate(WorldServer.class);
        set(WorldServer.class, world, "server", server);
        set(WorldServer.class, world, "manager", new PlayerChunkMap(world, 3));
        EntityTracker tracker = allocate(EntityTracker.class);
        set(EntityTracker.class, tracker, "trackedEntities", new Int2ObjectOpenHashMap<>());
        set(WorldServer.class, world, "tracker", tracker);
    }

    EntityPlayer player() throws Exception {
        return initializePlayer(allocate(EntityPlayer.class));
    }

    <T extends EntityPlayer> T initializePlayer(T player) throws Exception {
        int id = nextId++;
        UUID uuid = new UUID(0L, id);
        player.d(id);
        player.world = world;
        player.inventory = new PlayerInventory(player);
        set(Entity.class, player, "uniqueID", uuid);
        set(Entity.class, player, "datawatcher", new DataWatcher(player));
        set(EntityHuman.class, player, "bH", new GameProfile(uuid, "Viewer" + id));
        set(EntityLiving.class, player, "c", new AttributeMapServer());
        set(EntityLiving.class, player, "effects", new Int2ObjectOpenHashMap<>());
        set(EntityPlayer.class, player, "chunkCoordIntPairSet", new HashSet<ChunkCoordIntPair>());
        set(EntityPlayer.class, player, "removeQueue", new IntArrayFIFOQueue());
        set(EntityPlayer.class, player, "cancelledRemovals", new IntOpenHashSet());
        set(EntityPlayer.class, player, "playerInteractManager", new PlayerInteractManager(world));
        RecordingConnection connection = allocate(RecordingConnection.class);
        connection.packets = new ArrayList<>();
        player.playerConnection = connection;
        CraftPlayer wrapper = allocate(CraftPlayer.class);
        set(CraftEntity.class, wrapper, "entity", player);
        set(CraftPlayer.class, wrapper, "hiddenPlayers", new HashSet<UUID>());
        set(Entity.class, player, "bukkitEntity", wrapper);
        return player;
    }

    @SuppressWarnings("unchecked")
    void watchOriginChunk(EntityPlayer... players) throws Exception {
        Class<?> chunkType = Class.forName(PlayerChunkMap.class.getName() + "$PlayerChunk");
        Object chunk = allocate(chunkType);
        set(chunkType, chunk, "b", new HashSet<>(Arrays.asList(players)));
        set(chunkType, chunk, "location", new ChunkCoordIntPair(0, 0));
        Map<Long, Object> chunks = (Map<Long, Object>) field(PlayerChunkMap.class, "chunkMap")
                .get(world.getPlayerChunkMap());
        chunks.put(0L, chunk);
    }

    void listen(HandlerList handlers, Consumer<Event> action) {
        RegisteredListener listener = new RegisteredListener(new Listener() { }, (unused, event) -> {
            events.add(event);
            action.accept(event);
        }, EventPriority.NORMAL, plugin, false);
        listeners.add(listener);
        handlers.register(listener);
    }

    int count(Class<? extends Event> type) {
        int result = 0;
        for (Event event : events) {
            if (type.isInstance(event)) result++;
        }
        return result;
    }

    List<Packet> packets(EntityPlayer player) {
        return ((RecordingConnection) player.playerConnection).packets;
    }

    @Override
    public void close() throws Exception {
        for (RegisteredListener listener : listeners) {
            for (HandlerList handlers : HandlerList.getHandlerLists()) handlers.unregister(listener);
        }
        set(Bukkit.class, null, "server", oldBukkitServer);
        set(MinecraftServer.class, null, "l", oldMinecraftServer);
    }

    static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        field(owner, name).set(instance, value);
    }

    static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeClass, "theUnsafe").get(null);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    private static final class RecordingConnection extends PlayerConnection {
        private List<Packet> packets;

        private RecordingConnection() {
            super(null, null, null);
        }

        @Override
        public void sendPacket(Packet packet) {
            packets.add(packet);
        }
    }
}
