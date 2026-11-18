package ae.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {
    private static final int ACCOUNTS = 6;
    private static final int THREADS = 8;
    private static final int TRANSFERS = 300;
    private static final int OPENING = 1000;

    private void run(Isolation iso) throws Exception {
        MvccStore<String, Integer> store = new MvccStore<>();
        try (Transaction<String, Integer> t = store.begin()) {
            for (int i = 0; i < ACCOUNTS; i++) {
                t.put("acct" + i, OPENING);
            }
            t.commit();
        }
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?>[] fs = new Future<?>[THREADS];
            for (int w = 0; w < THREADS; w++) {
                long seed = w;
                fs[w] = pool.submit(() -> {
                    Random r = new Random(seed);
                    start.await();
                    for (int n = 0; n < TRANSFERS; n++) {
                        int from = r.nextInt(ACCOUNTS);
                        int to = (from + 1 + r.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
                        int amount = 1 + r.nextInt(20);
                        while (true) {
                            Transaction<String, Integer> t = store.begin(iso);
                            try {
                                int a = t.get("acct" + from).orElseThrow();
                                int b = t.get("acct" + to).orElseThrow();
                                t.put("acct" + from, a - amount);
                                t.put("acct" + to, b + amount);
                                t.commit();
                                break;
                            } catch (ConflictException e) {
                                conflicts.incrementAndGet();
                            } finally {
                                t.close();
                            }
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> f : fs) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        try (Transaction<String, Integer> t = store.begin()) {
            int total = 0;
            for (int v : t.scan("acct0", "acct~").values()) {
                total += v;
            }
            assertEquals(ACCOUNTS * OPENING, total, "money conserved (" + iso + ")");
        }
        assertEquals(0, store.activeTransactions());
        // 1 seed commit + one commit per successful transfer
        assertEquals(1 + THREADS * TRANSFERS, store.lastCommitTs());
        store.vacuum();
        assertEquals(ACCOUNTS, store.versionCount(), "vacuum leaves one version per account");
    }

    @Test
    void transfersConserveTotalUnderSnapshot() throws Exception {
        run(Isolation.SNAPSHOT);
    }

    @Test
    void transfersConserveTotalUnderSerializable() throws Exception {
        run(Isolation.SERIALIZABLE);
    }
}
