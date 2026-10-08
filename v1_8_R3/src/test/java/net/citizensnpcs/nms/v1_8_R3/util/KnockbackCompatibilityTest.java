package net.citizensnpcs.nms.v1_8_R3.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Random;

import org.bukkit.event.HandlerList;
import org.bukkit.util.Vector;
import org.github.paperspigot.PaperSpigotConfig;
import org.github.paperspigot.event.entity.EntityKnockbackByEntityEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.citizensnpcs.EventListen;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.CitizensPlugin;
import net.citizensnpcs.api.event.NPCKnockbackEvent;
import net.citizensnpcs.nms.v1_8_R3.entity.ZombieController.EntityZombieNPC;
import net.citizensnpcs.nms.v1_8_R3.entity.ZombieController.ZombieNPC;
import net.citizensnpcs.npc.CitizensNPC;
import net.minecraft.server.v1_8_R3.AttributeMapServer;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityLiving;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.GenericAttributes;

public class KnockbackCompatibilityTest {
    private NativeTestSupport server;
    private EntityZombieNPC target;
    private EntityPlayer attacker;
    private CitizensPlugin plugin;
    private Object oldImplementation;
    private double oldFriction, oldHorizontal, oldVertical, oldLimit;
    private NPCKnockbackEvent forwarded;
    private Vector originalImpulse;
    private Vector velocityDuringEvent;
    private boolean cancel;
    private Vector replacement;

    @Before
    public void setUp() throws Throwable {
        server = new NativeTestSupport();
        oldImplementation = NativeTestSupport.field(CitizensAPI.class, "instance").get(null);
        plugin = (CitizensPlugin) Proxy.newProxyInstance(CitizensPlugin.class.getClassLoader(),
                new Class<?>[] { CitizensPlugin.class }, (proxy, method, args) -> {
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return method.invoke(server.plugin, args);
                });
        NativeTestSupport.set(CitizensAPI.class, null, "instance", plugin);
        oldFriction = PaperSpigotConfig.knockbackFriction;
        oldHorizontal = PaperSpigotConfig.knockbackHorizontal;
        oldVertical = PaperSpigotConfig.knockbackVertical;
        oldLimit = PaperSpigotConfig.knockbackVerticalLimit;
        PaperSpigotConfig.knockbackFriction = 2;
        PaperSpigotConfig.knockbackHorizontal = 0.6;
        PaperSpigotConfig.knockbackVertical = 0.3;
        PaperSpigotConfig.knockbackVerticalLimit = 1;

        target = NativeTestSupport.allocate(EntityZombieNPC.class);
        target.d(200);
        target.motX = 0.8;
        target.motY = 0.2;
        target.motZ = -0.4;
        NativeTestSupport.set(Entity.class, target, "random", new Random(0));
        AttributeMapServer attributes = new AttributeMapServer();
        attributes.b(GenericAttributes.c).setValue(0);
        NativeTestSupport.set(EntityLiving.class, target, "c", attributes);
        NativeTestSupport.set(EntityZombieNPC.class, target, "npc", NativeTestSupport.allocate(CitizensNPC.class));
        NativeTestSupport.set(Entity.class, target, "bukkitEntity", new ZombieNPC(target));
        attacker = server.player();
        server.listen(NPCKnockbackEvent.getHandlerList(), event -> {
            forwarded = (NPCKnockbackEvent) event;
            originalImpulse = forwarded.getKnockbackVector().clone();
            velocityDuringEvent = new Vector(target.motX, target.motY, target.motZ);
            forwarded.setCancelled(cancel);
            if (replacement != null) forwarded.getKnockbackVector().copy(replacement);
        });

        // Invoke the real optional-event registration without constructing unrelated
        // world/chunk listeners. A narrow MethodHandle avoids resolving newer Bukkit
        // event classes present in unrelated EventListen method signatures.
        MethodHandles.Lookup lookup;
        try {
            Method privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class,
                    MethodHandles.Lookup.class);
            lookup = (MethodHandles.Lookup) privateLookupIn.invoke(null, EventListen.class, MethodHandles.lookup());
        } catch (NoSuchMethodException java8) {
            lookup = (MethodHandles.Lookup) NativeTestSupport.field(MethodHandles.Lookup.class, "IMPL_LOOKUP").get(null);
        }
        MethodHandle register = lookup.findVirtual(EventListen.class, "registerKnockbackEvent",
                MethodType.methodType(void.class, Class.class));
        register.invoke(NativeTestSupport.allocate(EventListen.class), EntityKnockbackByEntityEvent.class);
    }

    @After
    public void tearDown() throws Exception {
        if (plugin != null) HandlerList.unregisterAll(plugin);
        NativeTestSupport.set(CitizensAPI.class, null, "instance", oldImplementation);
        PaperSpigotConfig.knockbackFriction = oldFriction;
        PaperSpigotConfig.knockbackHorizontal = oldHorizontal;
        PaperSpigotConfig.knockbackVertical = oldVertical;
        PaperSpigotConfig.knockbackVerticalLimit = oldLimit;
        if (server != null) server.close();
    }

    @Test
    public void actualConfiguredImpulseIsForwardedOnceAndApplied() {
        assertTrue(target.a(attacker, 2.5F, 3, 4));

        assertEquals(1, server.count(NPCKnockbackEvent.class));
        assertSame(target.getNPC(), forwarded.getNPC());
        assertSame(attacker.getBukkitEntity(), forwarded.getKnockingBackEntity());
        assertEquals(2.5, forwarded.getStrength(), 0);
        assertVector(originalImpulse, -0.76, 0.2, -0.28);
        assertVector(velocityDuringEvent, 0.8, 0.2, -0.4);
        assertMotion(0.04, 0.4, -0.68);
    }

    @Test
    public void cancellationPreservesVelocityAndTheNativeBooleanResult() {
        cancel = true;
        // The native result means the resistance check passed, including cancelled events.
        assertTrue(target.a(attacker, 2.5F, 3, 4));
        assertEquals(1, server.count(NPCKnockbackEvent.class));
        assertMotion(0.8, 0.2, -0.4);
    }

    @Test
    public void listenerVectorEditsAreAppliedWithoutASecondSyntheticImpulse() {
        replacement = new Vector(0.1, 0.7, -0.2);
        assertTrue(target.a(attacker, 2.5F, 3, 4));
        assertEquals(1, server.count(NPCKnockbackEvent.class));
        assertMotion(0.9, 0.9, -0.6);
    }

    @Test
    public void resistanceReturnsFalseAndDoesNotInventAnNpcEvent() {
        target.getAttributeInstance(GenericAttributes.c).setValue(1);
        assertFalse(target.a(attacker, 2.5F, 3, 4));
        assertEquals(0, server.count(NPCKnockbackEvent.class));
        assertMotion(0.8, 0.2, -0.4);
    }

    private void assertMotion(double x, double y, double z) {
        assertVector(new Vector(target.motX, target.motY, target.motZ), x, y, z);
    }

    private static void assertVector(Vector vector, double x, double y, double z) {
        assertEquals(x, vector.getX(), 1.0E-9);
        assertEquals(y, vector.getY(), 1.0E-9);
        assertEquals(z, vector.getZ(), 1.0E-9);
    }
}
