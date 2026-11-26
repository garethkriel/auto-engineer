package ae.ot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded in-process simulation: clients and a server joined by per-client FIFO channels.
 * The scheduler picks, uniformly at random, which pending delivery (or local edit) runs next.
 */
public final class Simulation {

    private record Down(Server.Accepted acc, boolean ack) {}

    private final Random rnd;
    private final Server server;
    private final List<Client> clients = new ArrayList<>();
    private final List<ArrayDeque<Client.Send>> up = new ArrayList<>();
    private final List<ArrayDeque<Down>> down = new ArrayList<>();
    private int maxInFlight;

    public Simulation(long seed, String initial, int clientCount) {
        if (clientCount < 1) throw new IllegalArgumentException("need at least one client");
        rnd = new Random(seed);
        server = new Server(initial);
        for (int i = 0; i < clientCount; i++) {
            clients.add(new Client("c" + i, initial));
            up.add(new ArrayDeque<>());
            down.add(new ArrayDeque<>());
        }
    }

    public Server server() { return server; }

    public Client client(int i) { return clients.get(i); }

    /** Largest number of clients that had an unacknowledged edit at the same time. */
    public int maxInFlight() { return maxInFlight; }

    /** Applies {@code op} as a local edit of client {@code i}. */
    public void edit(int i, TextOp op) {
        client(i).local(op).ifPresent(up.get(i)::add);
        track();
    }

    private void track() {
        int n = 0;
        for (Client c : clients) if (c.state() != Client.State.SYNCHRONIZED) n++;
        maxInFlight = Math.max(maxInFlight, n);
    }

    /** Runs {@code edits} random local edits interleaved with random deliveries, then drains. */
    public void runRandom(int edits) {
        int left = edits;
        while (left > 0 || pending() > 0) {
            int p = pending();
            if (left > 0 && (p == 0 || rnd.nextInt(3) == 0)) {
                int i = rnd.nextInt(clients.size());
                edit(i, randomOp(client(i).document()));
                left--;
            } else {
                deliverRandom();
            }
        }
    }

    /** Delivers every pending message in random order. */
    public void runToQuiescence() {
        while (pending() > 0) deliverRandom();
    }

    private int pending() {
        int n = 0;
        for (int i = 0; i < clients.size(); i++) n += up.get(i).size() + down.get(i).size();
        return n;
    }

    private void deliverRandom() {
        int pick = rnd.nextInt(pending());
        for (int i = 0; i < clients.size(); i++) {
            if (pick < up.get(i).size()) { deliverUp(i); return; }
            pick -= up.get(i).size();
            if (pick < down.get(i).size()) { deliverDown(i); return; }
            pick -= down.get(i).size();
        }
        throw new IllegalStateException("scheduler fell through");
    }

    // FIFO per channel: always deliver the head of the chosen client's channel.
    private void deliverUp(int i) {
        Client.Send s = up.get(i).poll();
        Server.Accepted acc = server.receive(client(i).id(), s.baseRevision(), s.op());
        for (int j = 0; j < clients.size(); j++) down.get(j).add(new Down(acc, j == i));
    }

    private void deliverDown(int i) {
        Down d = down.get(i).poll();
        if (d.ack()) client(i).ack().ifPresent(up.get(i)::add);
        else client(i).remote(d.acc().op());
        track();
    }

    private TextOp randomOp(String doc) {
        TextOp.Builder b = new TextOp.Builder();
        int i = 0;
        while (i < doc.length()) {
            int n = Math.min(1 + rnd.nextInt(4), doc.length() - i);
            int k = rnd.nextInt(doc.length() > 30 ? 3 : 5);
            if (k < 2) { b.retain(n); i += n; }
            else if (k == 2) { b.delete(n); i += n; }
            else { b.insert(text()); }
        }
        if (rnd.nextInt(2) == 0 || doc.isEmpty()) b.insert(text());
        return b.build();
    }

    private String text() {
        int n = 1 + rnd.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) sb.append((char) ('a' + rnd.nextInt(26)));
        return sb.toString();
    }

    /** True when nothing is pending and every client holds the server's document. */
    public boolean converged() {
        if (pending() > 0) return false;
        for (Client c : clients) if (!c.document().equals(server.document())) return false;
        return true;
    }

    /** Test hook: replaces client {@code i} by a fresh client holding {@code text}. */
    public void corruptClientForTest(int i, String text) {
        clients.set(i, new Client(client(i).id(), text));
    }

    public String report() {
        StringBuilder sb = new StringBuilder("server rev " + server.revision() + ": \"" + server.document() + "\"\n");
        for (Client c : clients) {
            sb.append(c.id()).append(' ').append(c.state()).append(" rev ").append(c.revision())
              .append(": \"").append(c.document()).append("\"\n");
        }
        return sb.toString();
    }
}
