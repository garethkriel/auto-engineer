package ae.elevator;

import java.util.*;

/**
 * Deterministic tick-based simulation of an elevator bank. See TASK.md for the per-tick order of events.
 */
public final class Simulation {

    /** A pending hall call; ordering is (floor, direction). */
    private record Call(int floor, Direction dir) implements Comparable<Call> {
        @Override
        public int compareTo(Call o) {
            int c = Integer.compare(floor, o.floor);
            return c != 0 ? c : dir.compareTo(o.dir);
        }
    }

    private static final int UNASSIGNED = -1;
    private static final int STOP_PENALTY = 3;

    private final class Car {
        final int id;
        int floor = 0;
        Direction dir = Direction.UP;
        final TreeSet<Integer> stops = new TreeSet<>();
        final List<Passenger> riders = new ArrayList<>();

        Car(int id) {
            this.id = id;
        }
    }

    /** Boarding and delivery ticks of a finished (or partly finished) run. */
    public static final class Result {
        private final Map<String, Integer> board;
        private final Map<String, Integer> deliver;
        private final Map<String, Integer> arrival;

        private Result(Map<String, Integer> board, Map<String, Integer> deliver, Map<String, Integer> arrival) {
            this.board = Map.copyOf(board);
            this.deliver = Map.copyOf(deliver);
            this.arrival = Map.copyOf(arrival);
        }

        public int boardTick(String id) {
            return lookup(board, id, "not boarded");
        }

        public int deliverTick(String id) {
            return lookup(deliver, id, "not delivered");
        }

        public int waitTicks(String id) {
            return boardTick(id) - arrival.get(id);
        }

        public int rideTicks(String id) {
            return deliverTick(id) - boardTick(id);
        }

        public int deliveredCount() {
            return deliver.size();
        }

        private static int lookup(Map<String, Integer> m, String id, String what) {
            Integer v = m.get(id);
            if (v == null) throw new NoSuchElementException(what + ": " + id);
            return v;
        }
    }

    private final int floors;
    private final int capacity;
    private final List<Car> cars = new ArrayList<>();
    private final List<Passenger> all;
    private final Map<Integer, Deque<Passenger>> waiting = new HashMap<>();
    private final TreeMap<Call, Integer> calls = new TreeMap<>();
    private final Map<String, Integer> boardTicks = new HashMap<>();
    private final Map<String, Integer> deliverTicks = new HashMap<>();
    private final Map<String, Integer> arrivalTicks = new HashMap<>();
    private final Set<String> arrived = new HashSet<>();
    private int tick = 0;

    public Simulation(int floors, int cars, int capacity, List<Passenger> passengers) {
        if (floors < 2) throw new IllegalArgumentException("need at least two floors");
        if (cars < 1) throw new IllegalArgumentException("need at least one car");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        Objects.requireNonNull(passengers, "passengers");
        this.floors = floors;
        this.capacity = capacity;
        for (int i = 0; i < cars; i++) this.cars.add(new Car(i));
        Set<String> ids = new HashSet<>();
        for (Passenger p : passengers) {
            if (p.from() < 0 || p.from() >= floors || p.to() < 0 || p.to() >= floors) {
                throw new IllegalArgumentException("floor out of range for " + p.id());
            }
            if (!ids.add(p.id())) throw new IllegalArgumentException("duplicate id " + p.id());
            arrivalTicks.put(p.id(), p.arrivalTick());
        }
        this.all = List.copyOf(passengers);
    }

    public boolean done() {
        return deliverTicks.size() == all.size();
    }

    public Result result() {
        return new Result(boardTicks, deliverTicks, arrivalTicks);
    }

    /** Steps until everyone is delivered; throws if that takes more than {@code maxTicks} ticks. */
    public Result run(int maxTicks) {
        while (!done()) {
            if (tick >= maxTicks) throw new IllegalStateException("not finished after " + maxTicks + " ticks");
            step();
        }
        return result();
    }

    /** Processes one tick. */
    public void step() {
        for (Passenger p : all) {
            if (p.arrivalTick() == tick) {
                waiting.computeIfAbsent(p.from(), f -> new ArrayDeque<>()).addLast(p);
                arrived.add(p.id());
                calls.putIfAbsent(new Call(p.from(), p.direction()), UNASSIGNED);
            }
        }
        dispatch();
        for (Car car : cars) act(car);
        tick++;
    }

    private void dispatch() {
        for (Map.Entry<Call, Integer> e : calls.entrySet()) {
            if (e.getValue() != UNASSIGNED) continue;
            Car best = null;
            int bestCost = Integer.MAX_VALUE;
            for (Car c : cars) {
                if (cannotBoardHere(c, e.getKey())) continue;
                int cost = Math.abs(c.floor - e.getKey().floor()) + STOP_PENALTY * c.stops.size();
                if (cost < bestCost) {
                    best = c;
                    bestCost = cost;
                }
            }
            if (best == null) continue; // every car is stuck at the floor; retry next tick
            e.setValue(best.id);
            best.stops.add(e.getKey().floor());
        }
    }

    /**
     * A car standing at the call's floor that could not board anybody (full, or carrying riders in the other direction)
     * must not be given the call, or it would reopen its doors forever.
     */
    private boolean cannotBoardHere(Car c, Call call) {
        if (c.floor != call.floor()) return false;
        return c.riders.size() >= capacity || (!c.riders.isEmpty() && c.dir != call.dir());
    }

    private void act(Car car) {
        if (car.stops.contains(car.floor)) {
            openDoors(car);
        } else if (!car.stops.isEmpty()) {
            move(car);
        }
    }

    private void move(Car car) {
        if (car.dir == Direction.UP && car.stops.higher(car.floor) == null) car.dir = Direction.DOWN;
        else if (car.dir == Direction.DOWN && car.stops.lower(car.floor) == null) car.dir = Direction.UP;
        car.floor += car.dir == Direction.UP ? 1 : -1;
    }

    private void openDoors(Car car) {
        int f = car.floor;
        car.stops.remove(f);
        Iterator<Passenger> it = car.riders.iterator();
        while (it.hasNext()) {
            Passenger p = it.next();
            if (p.to() == f) {
                it.remove();
                deliverTicks.put(p.id(), tick);
            }
        }
        Deque<Passenger> queue = waiting.get(f);
        Direction boardDir = null;
        if (!car.riders.isEmpty()) boardDir = car.dir;
        else if (queue != null && !queue.isEmpty()) boardDir = queue.peekFirst().direction();
        if (boardDir != null && queue != null) {
            car.dir = boardDir;
            calls.remove(new Call(f, boardDir));
            Iterator<Passenger> qi = queue.iterator();
            boolean left = false;
            while (qi.hasNext()) {
                Passenger p = qi.next();
                if (p.direction() != boardDir) continue;
                if (car.riders.size() >= capacity) {
                    left = true;
                    continue;
                }
                qi.remove();
                car.riders.add(p);
                boardTicks.put(p.id(), tick);
                car.stops.add(p.to());
            }
            if (left) calls.put(new Call(f, boardDir), UNASSIGNED);
        }
        // a call this car was assigned at f but could not serve (wrong direction) must be re-dispatched
        for (Direction d : Direction.values()) {
            Call c = new Call(f, d);
            Integer owner = calls.get(c);
            if (owner != null && owner == car.id) calls.put(c, UNASSIGNED);
        }
    }

    /** Throws {@link IllegalStateException} naming the first violated invariant. */
    public void checkInvariants() {
        int riding = 0;
        for (Car c : cars) {
            if (c.floor < 0 || c.floor >= floors) throw new IllegalStateException("car floor out of range: " + c.id);
            if (c.riders.size() > capacity) throw new IllegalStateException("over capacity: car " + c.id);
            for (int s : c.stops) {
                if (s < 0 || s >= floors) throw new IllegalStateException("stop out of range: car " + c.id);
            }
            for (Passenger p : c.riders) {
                if (!c.stops.contains(p.to())) throw new IllegalStateException("rider destination not a stop: " + p.id());
                if (deliverTicks.containsKey(p.id())) throw new IllegalStateException("rider already delivered: " + p.id());
            }
            riding += c.riders.size();
        }
        int queued = 0;
        for (Map.Entry<Integer, Deque<Passenger>> e : waiting.entrySet()) {
            for (Passenger p : e.getValue()) {
                queued++;
                if (p.from() != e.getKey()) throw new IllegalStateException("passenger on wrong floor queue: " + p.id());
                if (!calls.containsKey(new Call(p.from(), p.direction()))) {
                    throw new IllegalStateException("waiting passenger without call: " + p.id());
                }
            }
        }
        if (arrived.size() != queued + riding + deliverTicks.size()) {
            throw new IllegalStateException("passenger accounting mismatch: arrived " + arrived.size()
                    + " queued " + queued + " riding " + riding + " delivered " + deliverTicks.size());
        }
        for (Map.Entry<Call, Integer> e : calls.entrySet()) {
            int owner = e.getValue();
            if (owner != UNASSIGNED && !cars.get(owner).stops.contains(e.getKey().floor())) {
                throw new IllegalStateException("assigned call whose floor is not a stop: " + e.getKey());
            }
        }
    }

    /** Test hook: wipes pending calls so the invariant checker has something to find. */
    void corruptDropCallsForTest() {
        calls.clear();
    }

    /** Test hook: empties every car's stops. */
    void corruptDropStopsForTest() {
        for (Car c : cars) c.stops.clear();
    }
}
