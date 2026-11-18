package ae.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Random operation sequences replayed on both implementations; every output must match. */
class DifferentialTest {
    private static final String[] MEMBERS = {"ann", "bob", "kim", "zoe", " ann ", "ghost", null};
    private static final String[] BOOKS = {"b1", "b2", "b3", " b1 ", "nope", null};
    private static final String[] TIERS = {"std", "PREM", " kid ", "gold", null, "Std"};

    @Test
    void randomSequencesAgree() {
        int ops = 0;
        Set<String> seen = new TreeSet<>();
        for (long seed = 1; seed <= 40; seed++) {
            Random r = new Random(seed);
            Api a = Api.legacy();
            Api b = Api.refactored();
            int day = 0;
            for (int i = 0; i < 400; i++, ops++) {
                if (r.nextInt(4) == 0) day += r.nextInt(12);
                String m = MEMBERS[r.nextInt(MEMBERS.length)];
                String k = BOOKS[r.nextInt(BOOKS.length)];
                int d = r.nextInt(30) == 0 ? -1 : day;
                String ctx = "seed " + seed + " op " + i + " m=" + m + " b=" + k + " day=" + d;
                String x;
                String y;
                switch (r.nextInt(8)) {
                    case 0 -> { int c = r.nextInt(4) - 1;
                        x = a.addBook(k, "t", c); y = b.addBook(k, "t", c); }
                    case 1 -> { String t = TIERS[r.nextInt(TIERS.length)];
                        x = a.join(m, t); y = b.join(m, t); }
                    case 2, 3 -> { x = a.checkout(m, k, d); y = b.checkout(m, k, d); }
                    case 4 -> { x = a.renew(m, k, d); y = b.renew(m, k, d); }
                    case 5 -> { int gd = day + r.nextInt(3) * 40;
                        x = a.giveBack(m, k, gd); y = b.giveBack(m, k, gd); }
                    case 6 -> { x = a.hold(m, k); y = b.hold(m, k); }
                    default -> { int c = r.nextInt(700) - 50;
                        x = a.payFine(m, c); y = b.payFine(m, c); }
                }
                assertEquals(x, y, ctx);
                seen.add(x.replaceAll("[0-9]+", "#"));
                assertState(a, b, ctx);
            }
        }
        assertEquals(16000, ops);
        for (String code : List.of("OK", "OK_ADDED", "DUE:#", "RETURNED", "RETURNED_FINE:#", "HOLD:#",
                "AVAILABLE", "HELD_FOR_OTHERS", "BLOCKED", "LIMIT", "UNAVAILABLE", "DUP_LOAN", "HAS_LOAN",
                "DUP_HOLD", "QUEUE_FULL", "MAX_RENEWALS", "HELD", "OVERDUE", "NO_LOAN", "PAID:# LEFT:#")) {
            assertTrue(seen.contains(code), "sequences never produced " + code + "; saw " + seen);
        }
    }

    private static void assertState(Api a, Api b, String ctx) {
        for (String m : MEMBERS) {
            assertEquals(a.owed(m), b.owed(m), ctx + " owed " + m);
            assertEquals(a.loansOf(m), b.loansOf(m), ctx + " loans " + m);
        }
        for (String k : BOOKS) {
            assertEquals(a.available(k), b.available(k), ctx + " avail " + k);
            assertEquals(a.queueLength(k), b.queueLength(k), ctx + " queue " + k);
        }
        assertEquals(List.of(), a.loansOf(null));
    }
}
