package ae.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Characterisation table: literal expectations recorded from the legacy behaviour. */
abstract class LibraryContract {
    abstract Api create();

    Api lib;

    @BeforeEach
    void setUp() {
        lib = create();
        assertEquals("OK", lib.addBook("b1", "One", 1));
        assertEquals("OK", lib.addBook("b2", "Two", 2));
        assertEquals("OK", lib.join("ann", "std"));
        assertEquals("OK", lib.join("bob", "PREM"));
        assertEquals("OK", lib.join("kim", " kid "));
    }

    @Test
    void addBookAndJoinValidation() {
        assertEquals("ERR_ID", lib.addBook(null, "x", 1));
        assertEquals("ERR_ID", lib.addBook("  ", "x", 1));
        assertEquals("ERR_COPIES", lib.addBook("b9", "x", 0));
        assertEquals("OK_ADDED", lib.addBook(" b1 ", "Other", 2));
        assertEquals(3, lib.available("b1"));
        assertEquals("ERR_NAME", lib.join(" ", "std"));
        assertEquals("ERR_TIER", lib.join("zed", "gold"));
        assertEquals("ERR_TIER", lib.join("zed", null));
        assertEquals("ERR_DUP", lib.join(" ann ", "kid"));
    }

    @Test
    void checkoutPeriodsPerTier() {
        assertEquals("DUE:24", lib.checkout("ann", "b1", 10));
        assertEquals("DUE:38", lib.checkout("bob", "b2", 10));
        assertEquals("DUE:17", lib.checkout("kim", "b2", 10));
        assertEquals(List.of("b1:24"), lib.loansOf("ann"));
        assertEquals(0, lib.available("b1"));
        assertEquals(0, lib.available("b2"));
    }

    @Test
    void checkoutGuardsInOrder() {
        assertEquals("ERR_DAY", lib.checkout("nobody", "nothing", -1));
        assertEquals("NO_MEMBER", lib.checkout("nobody", "nothing", 0));
        assertEquals("NO_MEMBER", lib.checkout(null, "b1", 0));
        assertEquals("NO_BOOK", lib.checkout("ann", "nothing", 0));
        assertEquals("NO_BOOK", lib.checkout("ann", null, 0));
        assertEquals("DUE:14", lib.checkout("ann", "b1", 0));
        assertEquals("DUP_LOAN", lib.checkout("ann", "b1", 0));
        assertEquals("UNAVAILABLE", lib.checkout("bob", "b1", 0));
    }

    @Test
    void loanLimits() {
        lib.addBook("c", "C", 9);
        lib.addBook("d", "D", 9);
        assertEquals("DUE:7", lib.checkout("kim", "b1", 0));
        assertEquals("DUE:7", lib.checkout("kim", "b2", 0));
        assertEquals("LIMIT", lib.checkout("kim", "c", 0));
        assertEquals("DUE:14", lib.checkout("ann", "c", 0));
        assertEquals("DUE:14", lib.checkout("ann", "d", 0));
        assertEquals("DUE:14", lib.checkout("ann", "b2", 0));
        assertEquals("LIMIT", lib.checkout("ann", "b1", 0));
        assertEquals(List.of("b2:14", "c:14", "d:14"), lib.loansOf("ann"));
    }

    @Test
    void renewRules() {
        assertEquals("NO_LOAN", lib.renew("ann", "b1", 0));
        assertEquals("ERR_DAY", lib.renew("ann", "b1", -2));
        assertEquals("NO_MEMBER", lib.renew("x", "b1", 0));
        lib.checkout("ann", "b1", 0);
        assertEquals("DUE:24", lib.renew("ann", "b1", 10));
        assertEquals("MAX_RENEWALS", lib.renew("ann", "b1", 11));
        lib.checkout("bob", "b2", 0);
        assertEquals("DUE:38", lib.renew("bob", "b2", 10));
        assertEquals("DUE:48", lib.renew("bob", "b2", 20));
        assertEquals("MAX_RENEWALS", lib.renew("bob", "b2", 21));
        lib.checkout("kim", "b2", 0);
        assertEquals("MAX_RENEWALS", lib.renew("kim", "b2", 1));
    }

    @Test
    void renewBlockedByHoldsAndOverdue() {
        lib.checkout("ann", "b1", 0);
        assertEquals("HOLD:1", lib.hold("bob", "b1"));
        assertEquals("HELD", lib.renew("ann", "b1", 1));
        lib.addBook("c", "C", 1);
        lib.checkout("bob", "c", 0);
        assertEquals("OVERDUE", lib.renew("bob", "c", 29));
        assertEquals("DUE:56", lib.renew("bob", "c", 28));
    }

    @Test
    void returnsAndFines() {
        lib.checkout("ann", "b1", 0);
        assertEquals("RETURNED", lib.giveBack("ann", "b1", 14));
        assertEquals("NO_LOAN", lib.giveBack("ann", "b1", 14));
        lib.checkout("ann", "b1", 0);
        assertEquals("RETURNED_FINE:100", lib.giveBack("ann", "b1", 18));
        assertEquals(100, lib.owed("ann"));
        lib.checkout("bob", "b1", 0);
        assertEquals("RETURNED_FINE:50", lib.giveBack("bob", "b1", 33));
        lib.checkout("kim", "b1", 0);
        assertEquals("RETURNED_FINE:5", lib.giveBack("kim", "b1", 8));
    }

    @Test
    void fineCapsAndBlocking() {
        lib.checkout("ann", "b1", 0);
        assertEquals("RETURNED_FINE:1000", lib.giveBack("ann", "b1", 500));
        assertEquals("BLOCKED", lib.checkout("ann", "b2", 500));
        assertEquals("PAID:400 LEFT:600", lib.payFine("ann", 400));
        assertEquals("BLOCKED", lib.checkout("ann", "b2", 500));
        assertEquals("PAID:100 LEFT:500", lib.payFine("ann", 100));
        assertEquals("BLOCKED", lib.checkout("ann", "b2", 500));
        assertEquals("PAID:1 LEFT:499", lib.payFine("ann", 1));
        assertEquals("DUE:514", lib.checkout("ann", "b2", 500));
        lib.checkout("kim", "b1", 0);
        assertEquals("RETURNED_FINE:300", lib.giveBack("kim", "b1", 400));
        assertEquals(300, lib.owed("kim"));
    }

    @Test
    void payFineEdges() {
        assertEquals("NO_MEMBER", lib.payFine("zz", 5));
        assertEquals("ERR_AMOUNT", lib.payFine("ann", 0));
        assertEquals("PAID:0 LEFT:0", lib.payFine("ann", 50));
        assertEquals(-1, lib.owed("zz"));
        assertEquals(-1, lib.available("zz"));
        assertEquals(-1, lib.queueLength("zz"));
        assertEquals(List.of(), lib.loansOf("zz"));
    }

    @Test
    void holdQueueFlow() {
        assertEquals("AVAILABLE", lib.hold("ann", "b1"));
        lib.checkout("ann", "b1", 0);
        assertEquals("HAS_LOAN", lib.hold("ann", "b1"));
        assertEquals("HOLD:1", lib.hold("bob", "b1"));
        assertEquals("DUP_HOLD", lib.hold("bob", "b1"));
        assertEquals("HOLD:2", lib.hold("kim", "b1"));
        lib.join("zoe", "std");
        lib.join("max", "std");
        assertEquals("HOLD:3", lib.hold("zoe", "b1"));
        assertEquals("QUEUE_FULL", lib.hold("max", "b1"));
        assertEquals(3, lib.queueLength("b1"));
        assertEquals("NO_MEMBER", lib.hold("q", "b1"));
        assertEquals("NO_BOOK", lib.hold("ann", "q"));
        lib.giveBack("ann", "b1", 1);
        assertEquals("HELD_FOR_OTHERS", lib.checkout("kim", "b1", 2));
        assertEquals("HELD_FOR_OTHERS", lib.checkout("ann", "b1", 2));
        assertEquals("DUE:30", lib.checkout("bob", "b1", 2));
        assertEquals(2, lib.queueLength("b1"));
        assertEquals("UNAVAILABLE", lib.checkout("kim", "b1", 2));
    }

    @Test
    void holdAllowedWhenCopyFreeButQueueNonEmpty() {
        lib.checkout("ann", "b1", 0);
        lib.hold("bob", "b1");
        lib.giveBack("ann", "b1", 1);
        assertEquals(1, lib.available("b1"));
        assertEquals("HOLD:2", lib.hold("kim", "b1"));
    }
}
