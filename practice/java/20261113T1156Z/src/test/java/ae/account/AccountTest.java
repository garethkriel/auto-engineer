package ae.account;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountTest {
    @BeforeEach
    void reset() {
        Account.resetForTests();
    }

    @Test
    void idsAreSequentialFromOneAndCountTracksStaticState() {
        Account a = Account.open("ann");
        Account b = Account.open("bob");
        assertEquals(1, a.id());
        assertEquals(2, b.id());
        assertEquals(2, Account.openCount());
    }

    @Test
    void resetRestartsSequence() {
        Account.open("x");
        Account.resetForTests();
        assertEquals(0, Account.openCount());
        assertEquals(1, Account.open("y").id());
    }

    @Test
    void instanceBalancesAreIndependent() {
        Account a = Account.open("ann");
        Account b = Account.open("bob");
        a.deposit(500);
        assertEquals(500, a.balanceCents());
        assertEquals(0, b.balanceCents());
    }

    @Test
    void invalidOwnerRejectedAndDoesNotConsumeId() {
        assertThrows(IllegalArgumentException.class, () -> Account.open(null));
        assertThrows(IllegalArgumentException.class, () -> Account.open("  "));
        assertEquals(0, Account.openCount());
        assertEquals(1, Account.open("ok").id());
    }

    @Test
    void depositAndWithdrawRejectNonPositive() {
        Account a = Account.open("ann");
        assertThrows(IllegalArgumentException.class, () -> a.deposit(0));
        assertThrows(IllegalArgumentException.class, () -> a.deposit(-1));
        assertThrows(IllegalArgumentException.class, () -> a.withdraw(0));
    }

    @Test
    void withdrawMoreThanBalanceFailsAndKeepsBalance() {
        Account a = Account.open("ann");
        a.deposit(100);
        assertThrows(IllegalStateException.class, () -> a.withdraw(101));
        assertEquals(100, a.balanceCents());
        a.withdraw(100);
        assertEquals(0, a.balanceCents());
    }

    @Test
    void depositOverflowThrowsAndKeepsBalance() {
        Account a = Account.open("ann");
        a.deposit(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> a.deposit(1));
        assertEquals(Long.MAX_VALUE, a.balanceCents());
    }

    @Test
    void transferMovesMoney() {
        Account a = Account.open("ann");
        Account b = Account.open("bob");
        a.deposit(1000);
        a.transferTo(b, 250);
        assertEquals(750, a.balanceCents());
        assertEquals(250, b.balanceCents());
    }

    @Test
    void failedTransferChangesNothing() {
        Account a = Account.open("ann");
        Account b = Account.open("bob");
        a.deposit(10);
        b.deposit(Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> a.transferTo(b, 11));
        assertThrows(ArithmeticException.class, () -> a.transferTo(b, 1));
        assertEquals(10, a.balanceCents());
        assertEquals(Long.MAX_VALUE, b.balanceCents());
    }

    @Test
    void transferToNullOrSelfRejected() {
        Account a = Account.open("ann");
        a.deposit(10);
        assertThrows(IllegalArgumentException.class, () -> a.transferTo(null, 1));
        assertThrows(IllegalArgumentException.class, () -> a.transferTo(a, 1));
    }

    @Test
    void equalityIsByIdOnly() {
        Account a = Account.open("ann");
        Account b = Account.open("ann");
        assertNotEquals(a, b);
        assertEquals(a, a);
        a.deposit(5);
        assertEquals(a.hashCode(), a.hashCode());
        assertNotEquals(a, null);
        assertNotEquals(a, "x");
    }

    @Test
    void toStringFormatsCents() {
        Account a = Account.open("ann");
        assertEquals("Account#1[ann: 0.00]", a.toString());
        a.deposit(1205);
        assertEquals("Account#1[ann: 12.05]", a.toString());
        a.deposit(5 - 0);
        assertEquals("Account#1[ann: 12.10]", a.toString());
    }
}
