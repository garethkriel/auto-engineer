package ae.elevator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SimulationTest {

    private static Passenger p(String id, int arr, int from, int to) {
        return new Passenger(id, arr, from, to);
    }

    // Trace A: car at 0. t0 dispatch stops{2}, move to 1. t1 move to 2. t2 doors: board (UP), stops{4}.
    // t3 floor 3, t4 floor 4, t5 doors: deliver. board=2, deliver=5.
    @Test
    void singlePassengerTraceBoardsAtTwoDeliversAtFive() {
        Simulation s = new Simulation(5, 1, 2, List.of(p("a", 0, 2, 4)));
        Simulation.Result r = s.run(100);
        assertEquals(2, r.boardTick("a"));
        assertEquals(5, r.deliverTick("a"));
        assertEquals(2, r.waitTicks("a"));
        assertEquals(3, r.rideTicks("a"));
    }

    // Trace B: passenger already at car floor 0. t0 doors board; t1 floor1, t2 floor2, t3 floor3, t4 doors deliver.
    @Test
    void passengerAtCarFloorBoardsImmediately() {
        Simulation.Result r = new Simulation(5, 1, 2, List.of(p("a", 0, 0, 3))).run(100);
        assertEquals(0, r.boardTick("a"));
        assertEquals(4, r.deliverTick("a"));
    }

    // Trace C (capacity 2, three UP riders 0->2 at t0): p0,p1 board t0 (FIFO), call re-registered, stops{2}.
    // t1 dispatch stops{2,0}, move to 1. t2 move 2. t3 doors: deliver p0,p1 at 3. stops{0}.
    // t4 reverse, floor 1. t5 floor 0. t6 doors: board p2 (car empty adopts UP). t7 floor1, t8 floor2, t9 doors deliver p2.
    @Test
    void capacityLeavesThirdPassengerForSecondTrip() {
        Simulation.Result r = new Simulation(5, 1, 2,
                List.of(p("p0", 0, 0, 2), p("p1", 0, 0, 2), p("p2", 0, 0, 2))).run(100);
        assertEquals(0, r.boardTick("p0"));
        assertEquals(0, r.boardTick("p1"));
        assertEquals(6, r.boardTick("p2"));
        assertEquals(3, r.deliverTick("p0"));
        assertEquals(3, r.deliverTick("p1"));
        assertEquals(9, r.deliverTick("p2"));
    }

    // Two cars, calls (0,UP)="near" and (4,DOWN)="far" at t0. Dispatch order is (floor,dir): (0,UP) first, cost 0 for
    // both cars -> tie -> car0. Then (4,DOWN): car0 cost 4+3*1=7, car1 cost 4 -> car1.
    // car0: t0 doors board near, stops{1}; t1 move 1; t2 doors deliver at 2.
    // car1: t0 floor1, t1 floor2, t2 floor3, t3 floor4, t4 doors board far.
    @Test
    void dispatchPicksByCostAndBreaksTiesByLowestId() {
        Simulation.Result r = new Simulation(5, 2, 4,
                List.of(p("far", 0, 4, 0), p("near", 0, 0, 1))).run(100);
        assertEquals(0, r.boardTick("near"));
        assertEquals(2, r.deliverTick("near"));
        assertEquals(4, r.boardTick("far"));
    }

    @Test
    void oppositeDirectionPassengersAtSameFloorBothServed() {
        Simulation.Result r = new Simulation(6, 1, 4,
                List.of(p("up", 0, 3, 5), p("down", 0, 3, 1))).run(200);
        assertEquals(2, r.deliveredCount());
        assertTrue(r.deliverTick("up") >= 2 + 3 && r.deliverTick("down") >= 2 + 3);
    }

    @Test
    void runThrowsWhenBoundTooSmall() {
        Simulation s = new Simulation(5, 1, 2, List.of(p("a", 0, 4, 0)));
        assertThrows(IllegalStateException.class, () -> s.run(3));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> new Simulation(5, 1, 2, List.of(p("a", 0, 2, 2))));
        assertThrows(IllegalArgumentException.class, () -> new Simulation(5, 1, 2, List.of(p("a", 0, 0, 5))));
        assertThrows(IllegalArgumentException.class, () -> new Simulation(5, 0, 2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Simulation(5, 1, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Simulation(5, 1, 2, List.of(p("a", 0, 0, 1), p("a", 1, 1, 0))));
    }

    @Test
    void randomRunsKeepInvariantsAndDeliverEveryoneNoEarlierThanPhysicsAllows() {
        for (long seed = 1; seed <= 60; seed++) {
            Random rnd = new Random(seed);
            int floors = 3 + rnd.nextInt(8);
            int cars = 1 + rnd.nextInt(3);
            int cap = 1 + rnd.nextInt(4);
            int n = 5 + rnd.nextInt(40);
            List<Passenger> ps = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int from = rnd.nextInt(floors);
                int to = rnd.nextInt(floors - 1);
                if (to >= from) to++;
                ps.add(p("p" + i, rnd.nextInt(60), from, to));
            }
            Simulation s = new Simulation(floors, cars, cap, ps);
            int guard = 0;
            while (!s.done()) {
                s.step();
                s.checkInvariants();
                assertTrue(++guard < 5000, "seed " + seed + " did not finish");
            }
            Simulation.Result r = s.result();
            assertEquals(n, r.deliveredCount(), "seed " + seed);
            for (Passenger q : ps) {
                assertTrue(r.boardTick(q.id()) >= q.arrivalTick(), "seed " + seed + " " + q);
                // board at arrival at best, |to-from| moves, one doors tick to deliver
                assertTrue(r.deliverTick(q.id()) >= q.arrivalTick() + Math.abs(q.from() - q.to()) + 1,
                        "seed " + seed + " " + q);
            }
        }
    }

    @Test
    void invariantCheckerRejectsCorruptState() {
        Simulation s = new Simulation(5, 1, 1, List.of(p("a", 0, 0, 3), p("b", 0, 0, 2)));
        s.step(); // a boards, b waits with a re-registered call
        s.checkInvariants();
        s.corruptDropCallsForTest();
        IllegalStateException e = assertThrows(IllegalStateException.class, s::checkInvariants);
        assertTrue(e.getMessage().startsWith("waiting passenger without call"), e.getMessage());
    }

    // Regression for a livelock found while writing this round: a car at floor 3 carrying a DOWN rider cannot board the
    // waiting UP passenger there, so the call must not be re-given to it at once.
    @Test
    void carCannotBoardOppositeDirectionCallAtItsFloorYetStillFinishes() {
        List<Passenger> ps = List.of(p("d", 0, 5, 1), p("u", 0, 3, 4), p("d2", 0, 3, 0));
        Simulation s = new Simulation(6, 1, 4, ps);
        int guard = 0;
        while (!s.done()) {
            s.step();
            s.checkInvariants();
            assertTrue(++guard < 300, "livelock");
        }
        assertEquals(3, s.result().deliveredCount());
    }

    @Test
    void heavyLoadWithFullCarsAlwaysFinishes() {
        for (long seed = 100; seed < 130; seed++) {
            Random rnd = new Random(seed);
            List<Passenger> ps = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                int from = rnd.nextInt(8);
                int to = rnd.nextInt(7);
                if (to >= from) to++;
                ps.add(p("q" + i, rnd.nextInt(10), from, to));
            }
            Simulation s = new Simulation(8, 2, 2, ps);
            while (!s.done()) {
                s.step();
                s.checkInvariants();
            }
            assertEquals(80, s.result().deliveredCount(), "seed " + seed);
        }
    }

    @Test
    void checkerRejectsOverCapacityStateViaDroppedStop() {
        Simulation s = new Simulation(5, 1, 2, List.of(p("a", 0, 0, 3)));
        s.step();
        s.checkInvariants();
        s.corruptDropStopsForTest();
        IllegalStateException e = assertThrows(IllegalStateException.class, s::checkInvariants);
        assertTrue(e.getMessage().startsWith("rider destination not a stop"), e.getMessage());
    }
}
