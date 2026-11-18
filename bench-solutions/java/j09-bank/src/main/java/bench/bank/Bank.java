package bench.bank;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Thread-safe in-memory bank; all operations are atomic under a single lock. */
public final class Bank {
    private final Map<String, BigDecimal> accounts = new HashMap<>();

    public Bank() {}

    public synchronized void open(String id, BigDecimal initial) {
        if (id == null || initial == null) {
            throw new IllegalArgumentException("null id or amount");
        }
        if (initial.signum() < 0) {
            throw new IllegalArgumentException("negative initial balance");
        }
        if (accounts.containsKey(id)) {
            throw new IllegalArgumentException("account exists: " + id);
        }
        accounts.put(id, initial);
    }

    public synchronized void transfer(String from, String to, BigDecimal amount) throws InsufficientFundsException {
        if (from == null || to == null || amount == null) {
            throw new IllegalArgumentException("null argument");
        }
        BigDecimal a = accounts.get(from);
        BigDecimal b = accounts.get(to);
        if (a == null || b == null) {
            throw new IllegalArgumentException("unknown account");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("same account");
        }
        if (a.compareTo(amount) < 0) {
            throw new InsufficientFundsException("insufficient funds in " + from);
        }
        accounts.put(from, a.subtract(amount));
        accounts.put(to, b.add(amount));
    }

    public synchronized BigDecimal balance(String id) {
        BigDecimal v = id == null ? null : accounts.get(id);
        if (v == null) {
            throw new IllegalArgumentException("unknown account: " + id);
        }
        return v;
    }
}
