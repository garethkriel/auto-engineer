package ae.ot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * A seeded, single-threaded simulation of one server and several clients connected by FIFO
 * channels. Each step either makes a random local edit on some client or delivers the oldest
 * message of one channel; after every delivery the invariants are checked.
 */
public final class OtSimulation {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz ";

    /** Summary of a completed run. */
    public record Report(int edits, boolean converged, int staleSubmissions, int committed) { }

    private sealed interface Down permits Ack, Remote { }
    private record Ack(int revision) implements Down { }
    private record Remote(int revision, TextOp op) implements Down { }

    private final Random rng;
    private final OtServer server;
    private final List<OtClient> clients = new ArrayList<>();
    private final List<Deque<OtClient.Message>> up = new ArrayList<>();
    private final List<Deque<Down>> down = new ArrayList<>();
    private int stale;
    private int committed;

    public OtSimulation(long seed, int clientCount, String initialDocument) {
        Objects.requireNonNull(initialDocument);
        if (clientCount < 1) throw new IllegalArgumentException("need at least one client");
        this.rng = new Random(seed);
        this.server = new OtServer(initialDocument);
        for (int i = 0; i < clientCount; i++) {
            clients.add(new OtClient(initialDocument));
            up.add(new ArrayDeque<>());
            down.add(new ArrayDeque<>());
        }
    }

    public String serverDocument() {
        return server.document();
    }

    public String clientDocument(int client) {
        return clients.get(client).document();
    }

    public List<TextOp> serverHistory() {
        return server.history();
    }

    /** Makes {@code edits} random edits interleaved with deliveries, then drains every channel. */
    public Report run(int edits) {
        int left = edits;
        while (true) {
            List<int[]> actions = new ArrayList<>();   // {kind, client}: 0 edit, 1 deliver up, 2 deliver down
            if (left > 0) for (int c = 0; c < clients.size(); c++) actions.add(new int[] {0, c});
            for (int c = 0; c < clients.size(); c++) {
                if (!up.get(c).isEmpty()) actions.add(new int[] {1, c});
                if (!down.get(c).isEmpty()) actions.add(new int[] {2, c});
            }
            if (actions.isEmpty()) break;
            int[] a = actions.get(rng.nextInt(actions.size()));
            switch (a[0]) {
                case 0 -> { edit(a[1]); left--; }
                case 1 -> deliverUp(a[1]);
                default -> deliverDown(a[1]);
            }
            List<String> bad = checkInvariants();
            if (!bad.isEmpty()) throw new IllegalStateException(String.join("; ", bad));
        }
        boolean converged = checkInvariants().isEmpty();
        for (OtClient c : clients) {
            converged &= c.document().equals(server.document()) && c.state() == OtClient.State.SYNCED;
        }
        return new Report(edits, converged, stale, committed);
    }

    private void edit(int c) {
        OtClient client = clients.get(c);
        TextOp op = RandomOps.generate(rng, client.document(), ALPHABET);
        client.localEdit(op).ifPresent(up.get(c)::add);
    }

    private void deliverUp(int c) {
        OtClient.Message m = up.get(c).poll();
        if (m.baseRevision() < server.revision()) stale++;
        OtServer.Result r = server.submit(m.baseRevision(), m.op());
        committed++;
        for (int i = 0; i < clients.size(); i++) {
            down.get(i).add(i == c ? new Ack(r.revision()) : new Remote(r.revision(), r.op()));
        }
    }

    private void deliverDown(int c) {
        Down d = down.get(c).poll();
        OtClient client = clients.get(c);
        switch (d) {
            case Ack a -> client.serverAck(a.revision()).ifPresent(up.get(c)::add);
            case Remote r -> client.serverOp(r.revision(), r.op());
        }
    }

    /**
     * Checks the protocol invariants and returns the violations (empty when all hold): the server
     * document equals the replayed history, and every client's document equals the server document at
     * the client's revision with the client's pending operations applied on top.
     */
    public List<String> checkInvariants() {
        List<String> bad = new ArrayList<>();
        String replay = server.initialDocument();
        for (TextOp o : server.history()) replay = o.apply(replay);
        if (!replay.equals(server.document())) bad.add("server document differs from replayed history");
        for (int c = 0; c < clients.size(); c++) {
            OtClient client = clients.get(c);
            String base = server.documentAt(client.revision());
            String expected = client.pending().isPresent() ? client.pending().get().apply(base) : base;
            if (!expected.equals(client.document())) bad.add("client " + c + " document differs from base+pending");
        }
        return bad;
    }

    /** Test hook: overwrites one client's document so the checker can be exercised. */
    void corruptClientDocumentForTest(int client, String doc) {
        clients.get(client).setDocumentForTest(doc);
    }
}
