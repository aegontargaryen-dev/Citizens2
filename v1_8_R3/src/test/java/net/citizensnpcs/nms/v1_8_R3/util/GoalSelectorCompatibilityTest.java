package net.citizensnpcs.nms.v1_8_R3.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.minecraft.server.v1_8_R3.PathfinderGoal;
import net.minecraft.server.v1_8_R3.PathfinderGoalSelector;

public class GoalSelectorCompatibilityTest {
    @Test
    public void clearingRunningGoalsStopsThemOnceAndPreventsFurtherTicks() throws Exception {
        try (NativeTestSupport server = new NativeTestSupport()) {
            PathfinderGoalSelector selector = new PathfinderGoalSelector(null);
            CountingGoal goal = new CountingGoal();
            selector.a(1, goal);
            tick(selector, 3);
            assertEquals(1, goal.starts);
            assertEquals(1, goal.ticks);

            NMSImpl.clearGoals(selector);
            NMSImpl.clearGoals(selector);
            tick(selector, 9);

            assertEquals(1, goal.stops);
            assertEquals(1, goal.starts);
            assertEquals(1, goal.ticks);

            CountingGoal replacement = new CountingGoal();
            selector.a(1, replacement);
            tick(selector, 3);
            assertEquals("Cleared goals must not retain a conflicting active lock", 1, replacement.starts);
            assertEquals(1, replacement.ticks);
        }
    }

    @Test
    public void clearingSeveralSelectorsAlsoRemovesGoalsThatNeverStarted() throws Exception {
        try (NativeTestSupport server = new NativeTestSupport()) {
            PathfinderGoalSelector first = new PathfinderGoalSelector(null);
            PathfinderGoalSelector second = new PathfinderGoalSelector(null);
            CountingGoal running = new CountingGoal();
            CountingGoal pending = new CountingGoal();
            first.a(1, running);
            second.a(1, pending);
            tick(first, 3);

            NMSImpl.clearGoals(first, second);
            tick(first, 6);
            tick(second, 6);

            assertEquals(1, running.stops);
            assertEquals(1, running.ticks);
            assertEquals(0, pending.starts);
            assertEquals(0, pending.ticks);
            assertEquals(0, pending.stops);
        }
    }

    private static void tick(PathfinderGoalSelector selector, int count) {
        for (int i = 0; i < count; i++) selector.a();
    }

    private static final class CountingGoal extends PathfinderGoal {
        private int starts;
        private int stops;
        private int ticks;

        CountingGoal() {
            a(1);
        }

        @Override
        public boolean a() {
            return true;
        }

        @Override
        public boolean b() {
            return true;
        }

        @Override
        public void c() {
            starts++;
        }

        @Override
        public void d() {
            stops++;
        }

        @Override
        public void e() {
            ticks++;
        }
    }
}
